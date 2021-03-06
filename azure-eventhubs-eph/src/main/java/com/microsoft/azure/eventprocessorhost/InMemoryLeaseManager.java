/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.eventprocessorhost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/***
 * An ILeaseManager implementation based on an in-memory store. 
 *
 * THIS CLASS IS PROVIDED AS A CONVENIENCE FOR TESTING ONLY. All data stored via this class is in memory
 * only and not persisted in any way. In addition, it is only visible within the same process: multiple
 * instances of EventProcessorHost in the same process will share the same in-memory store and leases
 * created by one will be visible to the others, but that is not true across processes.
 * 
 * With an ordinary store, there is a clear and distinct line between the values that are persisted
 * and the values that are live in memory. With an in-memory store, that line gets blurry. If we
 * accidentally hand out a reference to the in-store object, then the calling code is operating on
 * the "persisted" values without going through the manager and behavior will be very different.
 * Hence, the implementation takes pains to distinguish between references to "live" and "persisted"
 * checkpoints.
 * 
 * To use this class, create a new instance and pass it to the EventProcessorHost constructor that takes
 * ILeaseManager as an argument. After the EventProcessorHost instance is constructed, be sure to
 * call initialize() on this object before starting processing with EventProcessorHost.registerEventProcessor()
 * or EventProcessorHost.registerEventProcessorFactory().
 */
public class InMemoryLeaseManager implements ILeaseManager
{
    private EventProcessorHost host;
    private ExecutorService executor;

    private final static Logger TRACE_LOGGER = LoggerFactory.getLogger(InMemoryLeaseManager.class);

    public InMemoryLeaseManager()
    {
    	this.executor = Executors.newCachedThreadPool();
    }

    // This object is constructed before the EventProcessorHost and passed as an argument to
    // EventProcessorHost's constructor. So it has to get a reference to the EventProcessorHost later.
    public void initialize(EventProcessorHost host)
    {
        this.host = host;
    }

    @Override
    public int getLeaseRenewIntervalInMilliseconds()
    {
    	return this.host.getPartitionManagerOptions().getLeaseRenewIntervalInSeconds() * 1000;
    }
    
    @Override
    public int getLeaseDurationInMilliseconds()
    {
    	return this.host.getPartitionManagerOptions().getLeaseDurationInSeconds() * 1000;
    }

    @Override
    public Future<Boolean> leaseStoreExists()
    {
        return this.executor.submit(() -> leaseStoreExistsSync());
    }
    
    private Boolean leaseStoreExistsSync()
    {
    	return InMemoryLeaseStore.singleton.existsMap();
    }

    @Override
    public Future<Boolean> createLeaseStoreIfNotExists()
    {
        return this.executor.submit(() -> createLeaseStoreIfNotExistsSync());
    }

    private Boolean createLeaseStoreIfNotExistsSync()
    {
    	InMemoryLeaseStore.singleton.initializeMap(getLeaseDurationInMilliseconds());
        return true;
    }
    
    @Override
    public Future<Boolean> deleteLeaseStore()
    {
    	return this.executor.submit(() -> deleteLeaseStoreSync());
    }
    
    private Boolean deleteLeaseStoreSync()
    {
    	InMemoryLeaseStore.singleton.deleteMap();
    	return true;
    }
    
    @Override
    public Future<Lease> getLease(String partitionId)
    {
        return this.executor.submit(() -> getLeaseSync(partitionId));
    }

    private InMemoryLease getLeaseSync(String partitionId)
    {
    	InMemoryLease returnLease = null;
    	InMemoryLease leaseInStore = InMemoryLeaseStore.singleton.getLease(partitionId);
        if (leaseInStore == null)
        {
        	TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), partitionId, "getLease() no existing lease"));
        	returnLease = null;
        }
        else
        {
        	returnLease = new InMemoryLease(leaseInStore);
        }
        return returnLease;
    }

    @Override
    public Iterable<Future<Lease>> getAllLeases() throws Exception
    {
        ArrayList<Future<Lease>> leases = new ArrayList<Future<Lease>>();
        String[] partitionIds = this.host.getPartitionManager().getPartitionIds();
        for (String id : partitionIds)
        {
            leases.add(getLease(id));
        }
        return leases;
    }

    @Override
    public Future<Lease> createLeaseIfNotExists(String partitionId)
    {
        return this.executor.submit(() -> createLeaseIfNotExistsSync(partitionId));
    }

    private InMemoryLease createLeaseIfNotExistsSync(String partitionId)
    {
    	InMemoryLease leaseInStore = InMemoryLeaseStore.singleton.getLease(partitionId);
    	InMemoryLease returnLease = null;
        if (leaseInStore != null)
        {
        	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), partitionId,
                    "createLeaseIfNotExists() found existing lease, OK"));
        	returnLease = new InMemoryLease(leaseInStore);
        }
        else
        {
        	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), partitionId,
                    "createLeaseIfNotExists() creating new lease"));
        	InMemoryLease newStoreLease = new InMemoryLease(partitionId);
            newStoreLease.setEpoch(0L);
            newStoreLease.setOwner("");
            InMemoryLeaseStore.singleton.setOrReplaceLease(newStoreLease);
            returnLease = new InMemoryLease(newStoreLease);
        }
        return returnLease;
    }
    
    @Override
    public Future<Void> deleteLease(Lease lease)
    {
        return this.executor.submit(() -> deleteLeaseSync(lease));
    }
    
    private Void deleteLeaseSync(Lease lease)
    {
    	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "Deleting lease"));
    	InMemoryLeaseStore.singleton.removeLease((InMemoryLease)lease);
    	return null;
    }

    @Override
    public Future<Boolean> acquireLease(Lease lease)
    {
        return this.executor.submit(() -> acquireLeaseSync((InMemoryLease)lease));
    }

    private Boolean acquireLeaseSync(InMemoryLease lease)
    {
    	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "Acquiring lease"));
    	
    	boolean retval = true;
    	InMemoryLease leaseInStore = InMemoryLeaseStore.singleton.getLease(lease.getPartitionId());
        if (leaseInStore != null)
        {
        	InMemoryLease wasUnowned = InMemoryLeaseStore.singleton.atomicAquireUnowned(lease.getPartitionId(), this.host.getHostName());
            if (wasUnowned != null)
            {
            	// atomicAcquireUnowned already set ownership of the persisted lease, just update the live lease.
                lease.setOwner(this.host.getHostName());
            	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                        "acquireLease() acquired lease"));
            	leaseInStore = wasUnowned;
            	lease.setExpirationTime(leaseInStore.getExpirationTime());
            }
            else
            {
	            if (leaseInStore.getOwner().compareTo(this.host.getHostName()) == 0)
	            {
	            	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                            "acquireLease() already hold lease"));
	            }
	            else
	            {
	            	String oldOwner = leaseInStore.getOwner();
	            	// Make change in both persisted lease and live lease!
	            	leaseInStore.setOwner(this.host.getHostName());
	            	lease.setOwner(this.host.getHostName());
	            	TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                            "acquireLease() stole lease from " + oldOwner));
	            }
	            long newExpiration = System.currentTimeMillis() + getLeaseDurationInMilliseconds();
	        	// Make change in both persisted lease and live lease!
	            leaseInStore.setExpirationTime(newExpiration);
	            lease.setExpirationTime(newExpiration);
            }
        }
        else
        {
        	TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                    "acquireLease() can't find lease"));
        	retval = false;
        }
        
        return retval;
    }
    
    @Override
    public Future<Boolean> renewLease(Lease lease)
    {
        return this.executor.submit(() -> renewLeaseSync((InMemoryLease)lease));
    }
    
    private Boolean renewLeaseSync(InMemoryLease lease)
    {
    	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "Renewing lease"));
    	
    	boolean retval = true;
    	InMemoryLease leaseInStore = InMemoryLeaseStore.singleton.getLease(lease.getPartitionId());
        if (leaseInStore != null)
        {
        	// CHANGE TO MATCH BEHAVIOR OF AzureStorageCheckpointLeaseManager
        	// Renewing a lease that has expired succeeds unless some other host has grabbed it already.
        	// So don't check expiration, just ownership.
        	if (/* !wrapIsExpired(leaseInStore) && */ (leaseInStore.getOwner().compareTo(this.host.getHostName()) == 0))
        	{
                long newExpiration = System.currentTimeMillis() + getLeaseDurationInMilliseconds();
            	// Make change in both persisted lease and live lease!
                leaseInStore.setExpirationTime(newExpiration);
                lease.setExpirationTime(newExpiration);
        	}
        	else
            {
            	TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                        "renewLease() not renewed because we don't own lease"));
            	retval = false;
            }
        }
        else
        {
        	TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                    "renewLease() can't find lease"));
        	retval = false;
        }
        
        return retval;
    }

    @Override
    public Future<Boolean> releaseLease(Lease lease)
    {
        return this.executor.submit(() -> releaseLeaseSync((InMemoryLease)lease));
    }
    
    private Boolean releaseLeaseSync(InMemoryLease lease)
    {
    	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "Releasing lease"));
    	
    	boolean retval = true;
    	InMemoryLease leaseInStore = InMemoryLeaseStore.singleton.getLease(lease.getPartitionId());
    	if (leaseInStore != null)
    	{
    		if (!wrapIsExpired(leaseInStore) && (leaseInStore.getOwner().compareTo(this.host.getHostName()) == 0))
    		{
	    		TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "releaseLease() released OK"));
	        	// Make change in both persisted lease and live lease!
	    		leaseInStore.setOwner("");
	    		lease.setOwner("");
	    		leaseInStore.setExpirationTime(0);
	    		lease.setExpirationTime(0);
    		}
    		else
    		{
	    		TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                        "releaseLease() not released because we don't own lease"));
    			retval = false;
    		}
    	}
    	else
    	{
    		TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "releaseLease() can't find lease"));
    		retval = false;
    	}
    	return retval;
    }

    @Override
    public Future<Boolean> updateLease(Lease lease)
    {
        return this.executor.submit(() -> updateLeaseSync((InMemoryLease)lease));
    }
    
    private Boolean updateLeaseSync(InMemoryLease lease)
    {
    	TRACE_LOGGER.info(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(), "Updating lease"));
    	
    	// Renew lease first so it doesn't expire in the middle.
    	boolean retval = renewLeaseSync(lease);
    	
    	if (retval)
    	{
	    	InMemoryLease leaseInStore = InMemoryLeaseStore.singleton.getLease(lease.getPartitionId());
	    	if (leaseInStore != null)
	    	{
	    		if (!wrapIsExpired(leaseInStore) && (leaseInStore.getOwner().compareTo(this.host.getHostName()) == 0))
	    		{
	    			// We are updating with values already in the live lease, so only need to set on the persisted lease.
	   				leaseInStore.setEpoch(lease.getEpoch());
	    			leaseInStore.setToken(lease.getToken());
	    			// Don't copy expiration time, that is managed directly by Acquire/Renew/Release
	    		}
	    		else
	    		{
		    		TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                            "updateLease() not updated because we don't own lease"));
	    			retval = false;
	    		}
	    	}
	    	else
	    	{
	    		TRACE_LOGGER.warn(LoggingUtils.withHostAndPartition(this.host.getHostName(), lease.getPartitionId(),
                        "updateLease() can't find lease"));
	    		retval = false;
	    	}
    	}
    	
    	return retval;
    }
    
    private boolean wrapIsExpired(InMemoryLease lease)
    {
    	boolean retval = false;
    	try
    	{
    		retval = lease.isExpired();
    	}
    	catch (Exception e)
    	{
    		// InMemoryLease.isExpired cannot actually throw
    		// This only exists to keep the compiler happy
    	}
    	return retval;
    }


    private static class InMemoryLeaseStore
    {
        final static InMemoryLeaseStore singleton = new InMemoryLeaseStore();
        private static int leaseDurationInMilliseconds;

        private ConcurrentHashMap<String, InMemoryLease> inMemoryLeasesPrivate = null;
        
        synchronized boolean existsMap()
        {
        	return (this.inMemoryLeasesPrivate != null);
        }
        
        synchronized void initializeMap(int leaseDurationInMilliseconds)
        {
        	if (this.inMemoryLeasesPrivate == null)
        	{
        		this.inMemoryLeasesPrivate = new ConcurrentHashMap<String, InMemoryLease>();
        	}
        	InMemoryLeaseStore.leaseDurationInMilliseconds = leaseDurationInMilliseconds;
        }
        
        synchronized void deleteMap()
        {
        	this.inMemoryLeasesPrivate = null;
        }
        
        synchronized InMemoryLease getLease(String partitionId)
        {
        	return this.inMemoryLeasesPrivate.get(partitionId);
        }
        
        synchronized InMemoryLease atomicAquireUnowned(String partitionId, String newOwner)
        {
        	InMemoryLease leaseInStore = getLease(partitionId);
            try
            {
				if (leaseInStore.isExpired() || (leaseInStore.getOwner() == null) || leaseInStore.getOwner().isEmpty())
				{
					leaseInStore.setOwner(newOwner);
	                leaseInStore.setExpirationTime(System.currentTimeMillis() + InMemoryLeaseStore.leaseDurationInMilliseconds);
				}
				else
				{
					// Return null if it was already owned
					leaseInStore = null;
				}
			}
            catch (Exception e)
            {
        		// InMemoryLease.isExpired cannot actually throw
        		// This only exists to keep the compiler happy
			}
        	return leaseInStore;
        }
        
        synchronized void setOrReplaceLease(InMemoryLease newLease)
        {
        	this.inMemoryLeasesPrivate.put(newLease.getPartitionId(), newLease);
        }
        
        synchronized void removeLease(InMemoryLease goneLease)
        {
        	this.inMemoryLeasesPrivate.remove(goneLease.getPartitionId());
        }
    }
    
    
    private static class InMemoryLease extends Lease
    {
    	private long expirationTimeMillis = 0;
    	
		InMemoryLease(String partitionId)
		{
			super(partitionId);
		}
		
		InMemoryLease(InMemoryLease source)
		{
			super(source);
			this.expirationTimeMillis = source.expirationTimeMillis;
		}
		
		void setExpirationTime(long expireAtMillis)
		{
			this.expirationTimeMillis = expireAtMillis;
		}
		
		long getExpirationTime()
		{
			return this.expirationTimeMillis;
		}
		
		@Override
	    public boolean isExpired() throws Exception
	    {
			boolean hasExpired = (System.currentTimeMillis() >= this.expirationTimeMillis);
			if (hasExpired)
			{
	        	// CHANGE TO MATCH BEHAVIOR OF AzureStorageCheckpointLeaseManager
				// An expired lease can be renewed by the previous owner. In order to implement that behavior for
				// InMemory, the owner field has to remain unchanged.
				//setOwner("");
			}
			return hasExpired;
	    }
    }
}
