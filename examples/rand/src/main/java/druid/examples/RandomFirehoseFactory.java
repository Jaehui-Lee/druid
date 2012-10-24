package druid.examples;

import com.metamx.common.logger.Logger;
import com.metamx.druid.input.InputRow;
import com.metamx.druid.input.MapBasedInputRow;
import com.metamx.druid.realtime.Firehose;
import com.metamx.druid.realtime.FirehoseFactory;
import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.annotate.JsonTypeName;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

import static java.lang.Thread.sleep;


/** Random value sequence Firehost Factory named "rand".
 * Builds a Firehose that emits a stream of random numbers (outColumn, a positive double)
 * with timestamps along with an associated token (target).  This provides a timeseries
 * that requires no network access for demonstration, characterization, and testing.
 * The generated tuples can be thought of as asynchronously
 * produced triples (timestamp, outColumn, target) where the timestamp varies depending on
 * speed of processing.
 * <p>
 * InputRows are produced as fast as requested, so this can be used to determine the
 * upper rate of ingest if sleepUsec is set to 0; nTokens specifies how many associated
 * target labels are used.  Generation is round-robin for nTokens and sleep occurs
 * every nPerSleep values generated.  A random number seed can be used by setting the
 * firehose parameter "seed" to a non-zero value so that values can be reproducible
 * (but note that timestamp is not deterministic because timestamps are obtained at
 * the moment an event is delivered.)
 * Values are offset by adding the modulus of the token number to the random number
 * so that token values have distinct, non-overlapping ranges.
 *
 * </p>
 * Example spec file:
 * <pre>
 [{
   "schema" : { "dataSource":"randseq",
                "aggregators":[ {"type":"count", "name":"events"},
 	       		       {"type":"doubleSum","name":"outColumn","fieldName":"inColumn"} ],
                "indexGranularity":"minute",
 	       "shardSpec" : { "type": "none" } },
   "config" : { "maxRowsInMemory" : 50000,
                "intermediatePersistPeriod" : "PT2m" },

   "firehose" : { "type" : "rand",
                  "sleepUsec": 100000,
                  "maxGeneratedRows" : 5000000,
                  "seed" : 0,
                  "nTokens" : 19,
                  "nPerSleep" : 3
                 },

   "plumber" : { "type" : "realtime",
                 "windowPeriod" : "PT5m",
                 "segmentGranularity":"hour",
                 "basePersistDirectory" : "/tmp/realtime/basePersist" }
 }]
 * </pre>
 *
 * Example query using POST to /druid/v2/?w  (where w is an arbitrary parameter and the UTC date and time
 * MUST be adjusted for the current hour):
 * <pre>
 {
     "queryType": "topN",
     "dataSource": "randSeq",
     "granularity": "all",
     "dimension": "target",
     "threshold": 10,
     "metric": "randomNumberSum",
     "aggregations":[
     { "type": "count", "name": "rows"},
     { "type": "doubleSum", "fieldName": "events", "name": "e"},
     { "type": "doubleSum", "fieldName": "outColumn", "name": "randomNumberSum"}
     ],
     "postAggregations":[
     {"type":"arithmetic","name":"avg_random","fn":"/","fields":[{"type":"fieldAccess","name":"randomNumberSum","fieldName":"randomNumberSum"},{"type":"fieldAccess","name":"rows","fieldName":"rows"}]}
     ],
     "intervals":["2012-10-16T20:03/2012-10-16T21"]
 }
 * </pre>
 */
@JsonTypeName("rand")
public class RandomFirehoseFactory implements FirehoseFactory
{
  private static final Logger log = new Logger(RandomFirehoseFactory.class);
  /** msec to sleep before generating a new row; if this and delayNsec are 0, then go as fast as possible.
   * json param sleepUsec (microseconds) is used to initialize this.
   */
  private final long delayMsec;
  /** nsec to sleep before generating a new row; if this and delayMsec are 0, then go as fast as possible.
   * json param sleepUsec (microseconds) is used to initialize this.
  */
  private final int delayNsec;
  /** max rows to generate, -1 is infinite, 0 means nothing is generated; use this to prevent
   *  infinite space consumption or to see what happens when a Firehose stops delivering
   *  values, or to have hasMore() return false.
   */
  private final long maxGeneratedRows;
  /** seed for random number generator; if 0, then no seed is used. */
  private final long seed;
  /** number of tokens to randomly associate with values (no heap limits). This can be used to
   * stress test the number of tokens.
   */
  private final int nTokens;
  /** Number of token events per sleep interval.  */
  private final int nPerSleep;

  @JsonCreator
  public RandomFirehoseFactory(
      @JsonProperty("sleepUsec") Long sleepUsec,
      @JsonProperty("maxGeneratedRows") Long maxGeneratedRows,
      @JsonProperty("seed") Long seed,
      @JsonProperty("nTokens") Integer nTokens,
      @JsonProperty("nPerSleep") Integer nPerSleep
  )
  {
    long nsec = (sleepUsec > 0) ? sleepUsec * 1000L : 0;
    long msec = nsec / 1000000L;
    this.delayMsec = msec;
    this.delayNsec = (int)(nsec - (msec * 1000000L));
    this.maxGeneratedRows = maxGeneratedRows;
    this.seed = seed;
    this.nTokens = nTokens;
    this.nPerSleep = nPerSleep;
    if (nTokens <= 0) {
      log.warn("nTokens parameter " + nTokens +" ignored; must be greater than or equal to 1");
      nTokens = 1;
    }
    if (nPerSleep <= 0) {
      log.warn("nPerSleep parameter " + nPerSleep +" ignored; must be greater than or equal to 1");
      nPerSleep = 1;
    }
    log.info("maxGeneratedRows=" + maxGeneratedRows);
    log.info("seed=" + ( (seed == 0L) ? "random value" : seed ));
    log.info("nTokens=" + nTokens);
    log.info("nPerSleep=" + nPerSleep);
    double dmsec = (double)delayMsec + ((double)this.delayNsec)/1000000.;
    if (dmsec > 0.0) {
      log.info("sleep period=" + dmsec + "msec");
      log.info("approximate max rate of record generation=" + (nPerSleep * 1000./dmsec) + "/sec" +
        "  or  " + (60. * nPerSleep * 1000./dmsec) + "/minute"
        );
    } else {
      log.info("sleep period= NONE");
      log.info("approximate max rate of record generation= as fast as possible");
    }
  }

  @Override
  public Firehose connect() throws IOException
  {
    return new Firehose()
    {
      private final Runnable commitRunnable = new Runnable() { public void run() {} };
      private final java.util.Random rand = (seed == 0L) ? new Random() : new Random(seed);
      private final LinkedList<String> dimensions = new LinkedList<String>();
      private final boolean placeholderForAdd = dimensions.add("inColumn".toLowerCase());
      private final boolean placeholderForAdd2 = dimensions.add("target".toLowerCase());

      private final Map<String, Object> theMap = new HashMap<String, Object>(2);
      private long rowCount = 0L;
      private boolean waitIfmaxGeneratedRows = true;

      @Override
      public boolean hasMore()
      {
        if (maxGeneratedRows >= 0  &&  rowCount >= maxGeneratedRows) {
          return waitIfmaxGeneratedRows;
        } else {
          return true; // there are always more random numbers
        }
      }

      @Override
      public InputRow nextRow()
      {
        final long modulus = rowCount % nPerSleep;
        final long nth = (rowCount % nTokens) + 1;
        long sleepMsec = delayMsec;
        // all done?
        if (maxGeneratedRows >= 0  &&  rowCount >= maxGeneratedRows  &&  waitIfmaxGeneratedRows) {
          // sleep a long time instead of terminating
          sleepMsec = 2000000000L;
        }
        if (sleepMsec > 0L || delayNsec > 0) {
          try {
            if (modulus == 0) {
              sleep(sleepMsec, delayNsec);
            }
          } catch (InterruptedException e) {
            throw new RuntimeException("InterruptedException");
          }
        }
        rowCount++;
        theMap.put("inColumn".toLowerCase(), anotherRand((int)nth));
        theMap.put("target", ("a" + nth));
        return new MapBasedInputRow(System.currentTimeMillis(), dimensions, theMap);
      }

      private Float anotherRand(int scale)
      {
        double f = rand.nextDouble(); // [0.0,1.0]
        return new Float(f + (double)scale);
      }

      @Override
      public Runnable commit()
      {
        // Do nothing.
        return commitRunnable; // reuse the same object each time
      }

      @Override
      public void close() throws IOException
      {
          ; // do nothing
      }
    };
  }
}
