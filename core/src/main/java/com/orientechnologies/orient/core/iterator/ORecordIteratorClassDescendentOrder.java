/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *  
 */
package com.orientechnologies.orient.core.iterator;

import com.orientechnologies.orient.core.db.record.ODatabaseRecord;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.storage.OStorage;

/**
 * Record iterator to browse records in inverse order: from last to the first.
 * 
 * @author Luca Garulli
 */
public class ORecordIteratorClassDescendentOrder<REC extends ORecordInternal<?>> extends ORecordIteratorClass<REC> {
  public ORecordIteratorClassDescendentOrder(ODatabaseRecord iDatabase, ODatabaseRecord iLowLevelDatabase, String iClassName,
      boolean iPolymorphic, boolean iUseCache, boolean iterateThroughTombstones, OStorage.LOCKING_STRATEGY iLockingStrategy) {
    super(iDatabase, iLowLevelDatabase, iClassName, iPolymorphic, iUseCache, iterateThroughTombstones, iLockingStrategy);
  }

  @Override
  public ORecordIteratorClusters<REC> begin() {
    return super.last();
  }

  @Override
  public REC next() {
    return super.previous();
  }

  @Override
  public boolean hasNext() {
    return super.hasPrevious();
  }
}
