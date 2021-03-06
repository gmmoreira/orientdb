/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core;

import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.listener.OListenerManger;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.common.profiler.OProfilerMBean;
import com.orientechnologies.orient.core.command.script.OScriptManager;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.conflict.ORecordConflictStrategyFactory;
import com.orientechnologies.orient.core.db.ODatabaseFactory;
import com.orientechnologies.orient.core.db.ODatabaseLifecycleListener;
import com.orientechnologies.orient.core.db.ODatabaseThreadLocalFactory;
import com.orientechnologies.orient.core.engine.OEngine;
import com.orientechnologies.orient.core.engine.local.OEngineLocalPaginated;
import com.orientechnologies.orient.core.engine.memory.OEngineMemory;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.memory.OMemoryWatchDog;
import com.orientechnologies.orient.core.record.ORecordFactoryManager;
import com.orientechnologies.orient.core.storage.OStorage;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Orient extends OListenerManger<OOrientListener> {
  public static final String                                                           ORIENTDB_HOME          = "ORIENTDB_HOME";
  public static final String                                                           URL_SYNTAX             = "<engine>:<db-type>:<db-name>[?<db-param>=<db-value>[&]]*";

  protected static final Orient                                                        instance               = new Orient();
  protected static boolean                                                             registerDatabaseByPath = false;

  protected final ConcurrentMap<String, OEngine>                                       engines                = new ConcurrentHashMap<String, OEngine>();
  protected final ConcurrentMap<String, OStorage>                                      storages               = new ConcurrentHashMap<String, OStorage>();

  protected final Map<ODatabaseLifecycleListener, ODatabaseLifecycleListener.PRIORITY> dbLifecycleListeners   = new LinkedHashMap<ODatabaseLifecycleListener, ODatabaseLifecycleListener.PRIORITY>();
  protected final ODatabaseFactory                                                     databaseFactory        = new ODatabaseFactory();
  protected final OScriptManager                                                       scriptManager          = new OScriptManager();
  protected final Timer                                                                timer                  = new Timer(true);
  protected final ThreadGroup                                                          threadGroup            = new ThreadGroup(
                                                                                                                  "OrientDB");
  protected final AtomicInteger                                                        serialId               = new AtomicInteger();
  private final ReadWriteLock                                                          engineLock             = new ReentrantReadWriteLock();
  protected ORecordFactoryManager                                                      recordFactoryManager   = new ORecordFactoryManager();
  protected ORecordConflictStrategyFactory                                             recordConflictStrategy = new ORecordConflictStrategyFactory();
  protected OrientShutdownHook                                                         shutdownHook;
  protected OMemoryWatchDog                                                            memoryWatchDog;
  protected OProfilerMBean                                                             profiler               = new OProfiler();
  protected ODatabaseThreadLocalFactory                                                databaseThreadFactory;
  protected volatile boolean                                                           active                 = false;
  protected ThreadPoolExecutor                                                         workers;

  protected Orient() {
    super(true);

    startup();
  }

  public static Orient instance() {
    return instance;
  }

  public static String getHomePath() {
    String v = System.getProperty("orient.home");
    if (v == null)
      v = System.getProperty(ORIENTDB_HOME);
    if (v == null)
      v = System.getenv(ORIENTDB_HOME);

    return OFileUtils.getPath(v);
  }

  public static String getTempPath() {
    return OFileUtils.getPath(System.getProperty("java.io.tmpdir") + "/orientdb/");
  }

  /**
   * Tells if to register database by path. Default is false. Setting to true allows to have multiple databases in different path
   * with the same name.
   *
   * @see #setRegisterDatabaseByPath(boolean)
   * @return
   */
  public static boolean isRegisterDatabaseByPath() {
    return registerDatabaseByPath;
  }

  /**
   * Register database by path. Default is false. Setting to true allows to have multiple databases in different path with the same
   * name.
   *
   * @param iValue
   */
  public static void setRegisterDatabaseByPath(final boolean iValue) {
    registerDatabaseByPath = iValue;
  }

  public ORecordConflictStrategyFactory getRecordConflictStrategy() {
    return recordConflictStrategy;
  }

  public Orient startup() {
    engineLock.writeLock().lock();
    try {
      if (active)
        // ALREADY ACTIVE
        return this;

      shutdownHook = new OrientShutdownHook();

      final int cores = Runtime.getRuntime().availableProcessors();

      workers = new ThreadPoolExecutor(cores, cores * 3, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(cores * 500) {
        @Override
        public boolean offer(Runnable e) {
          // turn offer() and add() into a blocking calls (unless interrupted)
          try {
            put(e);
            return true;
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          return false;
        }
      });

      // REGISTER THE EMBEDDED ENGINE
      registerEngine(new OEngineLocalPaginated());
      registerEngine(new OEngineMemory());
      registerEngine("com.orientechnologies.orient.client.remote.OEngineRemote");

      if (OGlobalConfiguration.PROFILER_ENABLED.getValueAsBoolean())
        // ACTIVATE RECORDING OF THE PROFILER
        profiler.startRecording();

      if (OGlobalConfiguration.ENVIRONMENT_DUMP_CFG_AT_STARTUP.getValueAsBoolean())
        OGlobalConfiguration.dumpConfiguration(System.out);

      memoryWatchDog = new OMemoryWatchDog();

      active = true;
      return this;

    } finally {
      engineLock.writeLock().unlock();
    }
  }

  public Orient shutdown() {
    engineLock.writeLock().lock();
    try {
      if (!active)
        return this;

      active = false;

      workers.shutdown();

      OLogManager.instance().debug(this, "Orient Engine is shutting down...");

      // CALL THE SHUTDOWN ON ALL THE LISTENERS
      for (OOrientListener l : browseListeners()) {
        if (l != null)
          l.onShutdown();
      }

      closeAllStorages();

      // SHUTDOWN ENGINES
      for (OEngine engine : engines.values())
        engine.shutdown();
      engines.clear();

      if (databaseFactory != null)
        // CLOSE ALL DATABASES
        databaseFactory.shutdown();

      if (shutdownHook != null) {
        shutdownHook.cancel();
        shutdownHook = null;
      }

      if (threadGroup != null)
        // STOP ALL THE PENDING THREADS
        threadGroup.interrupt();

      resetListeners();

      timer.purge();

      profiler.shutdown();

      if (memoryWatchDog != null) {
        // SHUTDOWN IT AND WAIT FOR COMPETITION
        memoryWatchDog.sendShutdown();
        try {
          memoryWatchDog.join();
        } catch (InterruptedException e) {
        } finally {
          memoryWatchDog = null;
        }
      }

      OLogManager.instance().info(this, "OrientDB Engine shutdown complete");
      OLogManager.instance().flush();

    } finally {
      engineLock.writeLock().unlock();
    }
    return this;
  }

  public void closeAllStorages() {
    engineLock.writeLock().lock();
    try {
      // CLOSE ALL THE STORAGES
      final List<OStorage> storagesCopy = new ArrayList<OStorage>(storages.values());
      for (OStorage stg : storagesCopy) {
        try {
          OLogManager.instance().info(this, "- storage: " + stg.getName() + "...");
          stg.close(true, false);
        } catch (Throwable e) {
          OLogManager.instance().warn(this, "-- error on closing storage", e);
        }
      }
      storages.clear();
    } finally {
      engineLock.writeLock().unlock();
    }
  }

  public ThreadPoolExecutor getWorkers() {
    return workers;
  }

  public OStorage loadStorage(String iURL) {
    if (iURL == null || iURL.length() == 0)
      throw new IllegalArgumentException("URL missed");

    if (iURL.endsWith("/"))
      iURL = iURL.substring(0, iURL.length() - 1);

    // SEARCH FOR ENGINE
    int pos = iURL.indexOf(':');
    if (pos <= 0)
      throw new OConfigurationException("Error in database URL: the engine was not specified. Syntax is: " + URL_SYNTAX
          + ". URL was: " + iURL);

    final String engineName = iURL.substring(0, pos);

    engineLock.readLock().lock();
    try {
      final OEngine engine = engines.get(engineName.toLowerCase());

      if (engine == null)
        throw new OConfigurationException("Error on opening database: the engine '" + engineName + "' was not found. URL was: "
            + iURL + ". Registered engines are: " + engines.keySet());

      // SEARCH FOR DB-NAME
      iURL = iURL.substring(pos + 1);
      pos = iURL.indexOf('?');

      Map<String, String> parameters = null;
      String dbPath = null;
      if (pos > 0) {
        dbPath = iURL.substring(0, pos);
        iURL = iURL.substring(pos + 1);

        // PARSE PARAMETERS
        parameters = new HashMap<String, String>();
        String[] pairs = iURL.split("&");
        String[] kv;
        for (String pair : pairs) {
          kv = pair.split("=");
          if (kv.length < 2)
            throw new OConfigurationException("Error on opening database: parameter has no value. Syntax is: " + URL_SYNTAX
                + ". URL was: " + iURL);
          parameters.put(kv[0], kv[1]);
        }
      } else
        dbPath = iURL;

      final String dbName = registerDatabaseByPath ? dbPath : OIOUtils.getRelativePathIfAny(dbPath, null);

      OStorage storage;
      if (engine.isShared()) {
        // SEARCH IF ALREADY USED
        storage = storages.get(dbName);
        if (storage == null) {
          // NOT FOUND: CREATE IT
          storage = engine.createStorage(dbPath, parameters);

          final OStorage oldStorage = storages.putIfAbsent(dbName, storage);
          if (oldStorage != null)
            storage = oldStorage;
        }
      } else {
        // REGISTER IT WITH A SERIAL NAME TO AVOID BEING REUSED
        storage = engine.createStorage(dbPath, parameters);
        storages.put(dbName + "__" + serialId.incrementAndGet(), storage);
      }

      for (OOrientListener l : browseListeners())
        l.onStorageRegistered(storage);

      return storage;
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public OStorage registerStorage(OStorage storage) throws IOException {
    engineLock.readLock().lock();
    try {
      for (OOrientListener l : browseListeners())
        l.onStorageRegistered(storage);

      OStorage oldStorage = storages.putIfAbsent(storage.getName(), storage);
      if (oldStorage != null)
        storage = oldStorage;

      return storage;
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public OStorage getStorage(final String dbName) {
    engineLock.readLock().lock();
    try {
      return storages.get(dbName);
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public void registerEngine(final OEngine iEngine) {
    engineLock.readLock().lock();
    try {
      engines.put(iEngine.getName(), iEngine);
    } finally {
      engineLock.readLock().unlock();
    }
  }

  /**
   * Returns the engine by its name.
   * 
   * @param engineName
   *          Engine name to retrieve
   * @return OEngine instance of found, otherwise null
   */
  public OEngine getEngine(final String engineName) {
    engineLock.readLock().lock();
    try {
      return engines.get(engineName);
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public Set<String> getEngines() {
    engineLock.readLock().lock();
    try {
      return Collections.unmodifiableSet(engines.keySet());
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public void unregisterStorageByName(final String name) {
    final String dbName = registerDatabaseByPath ? name : OIOUtils.getRelativePathIfAny(name, null);
    final OStorage stg = storages.get(dbName);
    unregisterStorage(stg);
  }

  public void unregisterStorage(final OStorage storage) {
    if (!active)
      // SHUTDOWNING OR NOT ACTIVE: RETURN
      return;

    if (storage == null)
      return;

    engineLock.writeLock().lock();
    try {
      // UNREGISTER ALL THE LISTENER ONE BY ONE AVOIDING SELF-RECURSION BY REMOVING FROM THE LIST
      final Iterable<OOrientListener> listenerCopy = getListenersCopy();
      for (Iterator<OOrientListener> it = listenerCopy.iterator(); it.hasNext();) {
        final OOrientListener l = it.next();
        unregisterListener(l);
        l.onStorageUnregistered(storage);
      }

      final List<String> storagesToRemove = new ArrayList<String>();

      for (Entry<String, OStorage> s : storages.entrySet()) {
        if (s.getValue().equals(storage))
          storagesToRemove.add(s.getKey());
      }

      for (String dbName : storagesToRemove)
        storages.remove(dbName);

    } finally {
      engineLock.writeLock().unlock();
    }
  }

  public Collection<OStorage> getStorages() {
    engineLock.readLock().lock();
    try {
      return new ArrayList<OStorage>(storages.values());
    } finally {
      engineLock.readLock().unlock();
    }
  }

  public Timer getTimer() {
    return timer;
  }

  public void removeShutdownHook() {
    if (shutdownHook != null)
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
  }

  public Iterator<ODatabaseLifecycleListener> getDbLifecycleListeners() {
    return dbLifecycleListeners.keySet().iterator();
  }

  public void addDbLifecycleListener(final ODatabaseLifecycleListener iListener) {
    final Map<ODatabaseLifecycleListener, ODatabaseLifecycleListener.PRIORITY> tmp = new LinkedHashMap<ODatabaseLifecycleListener, ODatabaseLifecycleListener.PRIORITY>(
        dbLifecycleListeners);
    tmp.put(iListener, iListener.getPriority());
    dbLifecycleListeners.clear();
    for (ODatabaseLifecycleListener.PRIORITY p : ODatabaseLifecycleListener.PRIORITY.values()) {
      for (Map.Entry<ODatabaseLifecycleListener, ODatabaseLifecycleListener.PRIORITY> e : tmp.entrySet()) {
        if (e.getValue() == p)
          dbLifecycleListeners.put(e.getKey(), e.getValue());
      }
    }
  }

  public void removeDbLifecycleListener(final ODatabaseLifecycleListener iListener) {
    dbLifecycleListeners.remove(iListener);
  }

  public ThreadGroup getThreadGroup() {
    return threadGroup;
  }

  public ODatabaseThreadLocalFactory getDatabaseThreadFactory() {
    return databaseThreadFactory;
  }

  public OMemoryWatchDog getMemoryWatchDog() {
    return memoryWatchDog;
  }

  public ORecordFactoryManager getRecordFactoryManager() {
    return recordFactoryManager;
  }

  public void setRecordFactoryManager(final ORecordFactoryManager iRecordFactoryManager) {
    recordFactoryManager = iRecordFactoryManager;
  }

  public ODatabaseFactory getDatabaseFactory() {
    return databaseFactory;
  }

  public OProfilerMBean getProfiler() {
    return profiler;
  }

  public void setProfiler(final OProfilerMBean iProfiler) {
    profiler = iProfiler;
  }

  public void registerThreadDatabaseFactory(final ODatabaseThreadLocalFactory iDatabaseFactory) {
    databaseThreadFactory = iDatabaseFactory;
  }

  public OScriptManager getScriptManager() {
    return scriptManager;
  }

  private void registerEngine(final String iClassName) {
    try {
      final Class<?> cls = Class.forName(iClassName);
      registerEngine((OEngine) cls.newInstance());
    } catch (Exception e) {
    }
  }
}
