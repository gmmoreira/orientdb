package com.orientechnologies.orient.core.storage.impl.local.paginated.wal;

import java.io.IOException;
import java.util.Set;

/**
 * @author Andrey Lomakin <a href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 * @since 6/25/14
 */
public interface OWriteAheadLog {
  OLogSequenceNumber logFuzzyCheckPointStart() throws IOException;

  OLogSequenceNumber logFuzzyCheckPointEnd() throws IOException;

  OLogSequenceNumber logFullCheckpointStart() throws IOException;

  OLogSequenceNumber logFullCheckpointEnd() throws IOException;

  void logDirtyPages(Set<ODirtyPage> dirtyPages) throws IOException;

  OLogSequenceNumber getLastCheckpoint();

  OLogSequenceNumber begin() throws IOException;

  OLogSequenceNumber end() throws IOException;

  void flush();

  OLogSequenceNumber log(OWALRecord record) throws IOException;

  void truncate() throws IOException;

  void close() throws IOException;

  void close(boolean flush) throws IOException;

  void delete() throws IOException;

  void delete(boolean flush) throws IOException;

  OWALRecord read(OLogSequenceNumber lsn) throws IOException;

  OLogSequenceNumber next(OLogSequenceNumber lsn) throws IOException;

  OLogSequenceNumber getFlushedLSN();

  void cutTill(OLogSequenceNumber lsn) throws IOException;
}
