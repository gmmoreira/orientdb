package com.orientechnologies.orient.test.database.speed;

import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.ORecordSerializerBinary;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author Andrey Lomakin <a href="mailto:lomakin.andrey@gmail.com">Andrey Lomakin</a>
 * @since 8/19/14
 */
@Test
public class LocalMTCreateDocumentSpeedTest {
  private ODatabaseDocumentTx database;
  private Date                date            = new Date();
  private CountDownLatch      latch           = new CountDownLatch(1);
  private List<Future>        futures;
  private volatile boolean    stop            = false;
  private ExecutorService     executorService = Executors.newCachedThreadPool();

  @BeforeClass
  public void init() {
    OGlobalConfiguration.USE_WAL.setValue(false);

    database = new ODatabaseDocumentTx(System.getProperty("url"));
    database.setSerializer(new ORecordSerializerBinary());

    if (database.exists()) {
      database.open("admin", "admin");
      database.drop();
    }

    database.create();
    database.set(ODatabase.ATTRIBUTES.MINIMUMCLUSTERS, 8);

    database.getMetadata().getSchema().createClass("Account");

    futures = new ArrayList<Future>();
    for (int i = 0; i < 8; i++)
      futures.add(executorService.submit(new Saver()));
  }

  public void cycle() throws Exception {
    long start = System.currentTimeMillis();
    latch.countDown();

    Thread.sleep(10 * 60 * 1000);
    stop = true;

    for (Future future : futures)
      future.get();

    long end = System.currentTimeMillis();

    long sum = database.countClass("Account");

    System.out.println(sum / (end - start));

  }

  @AfterClass
  public void deinit() {
    System.out.println(Orient.instance().getProfiler().dump());

    if (database != null)
      database.close();
  }

  private final class Saver implements Callable<Void> {

    private Saver() {
    }

    @Override
    public Void call() throws Exception {
      ODatabaseDocumentTx database = new ODatabaseDocumentTx(System.getProperty("url"));
      database.open("admin", "admin");

      latch.await();

      while (!stop) {
        ODocument record = new ODocument("Account");
        record.field("id", 1);
        record.field("name", "Luca");
        record.field("surname", "Garulli");
        record.field("birthDate", date);
        record.field("salary", 3000f);
        record.save();
      }

      database.close();
      return null;
    }
  }

}