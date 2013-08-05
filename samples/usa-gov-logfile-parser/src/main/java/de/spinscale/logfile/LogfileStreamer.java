package de.spinscale.logfile;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.geo.GeoHashUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.node.NodeBuilder;
import org.elasticsearch.metrics.ElasticsearchReporter;

import java.io.InputStream;
import java.lang.Exception;
import java.lang.System;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.client.Requests.indexRequest;

/*
curl http://localhost:9200/_percolator/metrics/indexing-request-monitor -X PUT -d '{"query":{"bool":{"must":[{"term":{"name":"usagov-indexing-requests"}},{"range":{"mean_rate":{"from":"0.10","include_lower":false}}}]}}}'
 */
public class LogfileStreamer {

    private static final String INDEX = "logfile";
    private static final String TYPE = "log";
    private static final int MAX_BULK_SIZE = 100;
    private static final String ISO_8601_DATE_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ssZ";
    private static final DateFormat ISO_8601_DATE_FORMAT = new SimpleDateFormat(ISO_8601_DATE_FORMAT_STRING, Locale.US);

    private final TransportClient client;
    private BulkRequestBuilder bulk;
    private StopWatch sw;
    private long startTimestamp;

    // statistics
    private final MetricRegistry registry = new MetricRegistry();
    private final Meter entryMeter = registry.meter("usagov-incoming-requests");
    private final Meter indexingMeter = registry.meter("usagov-indexing-requests");
    private final Counter heartbeatCounter = registry.counter("usa-gov-heartbearts-count");
    private final Timer bulkRequestTimer = registry.timer("bulk-request-timer");

    public static void main(String[] args) throws Exception {
        new LogfileStreamer().run();
    }

    public LogfileStreamer() {
        client = new TransportClient(ImmutableSettings.settingsBuilder().put("cluster.name", "metrics").build()).addTransportAddress(new
                InetSocketTransportAddress("localhost", 9300));
        reset();
    }

    public void run() throws Exception {
        startElasticsearchIfNecessary();
        createIndexAndMappingIfNecessary();

        // index into the metrics index without date formatting
        ElasticsearchReporter reporter = ElasticsearchReporter.forRegistry(registry)
                .hosts("localhost:9200")
                .indexDateFormat("")
                .percolateNotifier(new HttpNotifier())
                .percolateMetrics(".*")
                .build();
        reporter.start(60, TimeUnit.SECONDS);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        ObjectReader reader = objectMapper.reader(Map.class);
        MappingIterator<Map<String, Object>> iterator = reader.readValues(getInputStream());

        try {
            while (iterator.hasNextValue()) {
                Map<String, Object> entry = iterator.nextValue();
                if (entry.containsKey("_heartbeat_")) {
                    heartbeatCounter.inc();
                    continue;
                }

                if (entry.containsKey("ll") && entry.containsKey("t")) {
                    List<Double> location = (List<Double>) entry.get("ll");
                    long timestamp = ((Integer) entry.get("t")).longValue();
                    double latitude = location.get(0);
                    double longitude = location.get(1);

                    addToBulkRequest(timestamp, latitude, longitude);
                    entryMeter.mark(1);
                }
            }
        } finally {
            executeBulkRequest();
        }
    }

    private void addToBulkRequest(long timestamp, double latitude, double longitude) {
        String geohash = GeoHashUtils.encode(latitude, longitude);
        String isoDate = ISO_8601_DATE_FORMAT.format(new Date(timestamp * 1000));
        String json = String.format("{\"date\":\"%s\", \"geohash\":\"%s\" }", isoDate, geohash);
        bulk.add(indexRequest().index(INDEX).type(TYPE).source(json));
        System.out.print(".");

        executeBulkRequest();
    }

    private void executeBulkRequest() {
        if (bulk.numberOfActions() == 0) return;
        long secondsSinceLastUpdate = System.currentTimeMillis() / 1000 - startTimestamp;
        if (bulk.numberOfActions() < MAX_BULK_SIZE && secondsSinceLastUpdate < 10) return;

        BulkResponse bulkResponse = null;
        final Timer.Context context = bulkRequestTimer.time();
        try {
            bulkResponse = bulk.execute().actionGet();
        } finally {
            context.stop();
        }
        logStatistics(bulkResponse.getItems().length);
        reset();
    }

    private void logStatistics(long itemsIndexed) {
        long totalTimeInSeconds = sw.stop().totalTime().seconds();
        double totalDocumentsPerSecond = (totalTimeInSeconds == 0) ? itemsIndexed: (double) itemsIndexed / totalTimeInSeconds;
        System.out.println(String.format("\nIndexed %s documents, %.2f per second in %s seconds", itemsIndexed, totalDocumentsPerSecond, totalTimeInSeconds));
        indexingMeter.mark(1);
    }

    private void reset() {
        sw = new StopWatch().start();
        startTimestamp = System.currentTimeMillis() / 1000;
        bulk = client.prepareBulk();
    }

    private InputStream getInputStream() throws Exception {
        URL url = new URL("http://developer.usa.gov/1usagov");
        HttpURLConnection request = (HttpURLConnection) url.openConnection();
        return request.getInputStream();
    }

    private void createIndexAndMappingIfNecessary() {
        try {
            client.admin().indices().prepareCreate("logfile").execute().actionGet();
        } catch (Exception e) {}

        try {
            XContentBuilder mappingContent = XContentFactory.jsonBuilder().startObject().startObject("log")
                    .startObject("properties")
                    .startObject("geohash").field("type", "geo_point").field("geohash", true).endObject()
                    .endObject()
                    .endObject().endObject();

            client.admin().indices().preparePutMapping("logfile").setType("log").setSource(mappingContent).execute().actionGet();
        } catch (Exception e) {}
    }

    private void startElasticsearchIfNecessary() {
        if (!"no".equals(System.getProperty("create.es.instance"))) {
            System.out.println("Starting elasticsearch instance");
            NodeBuilder.nodeBuilder().settings(ImmutableSettings.settingsBuilder().put("cluster.name", "metrics").build()).node().start();
        } else {
            System.out.println("Not starting elasticsearch instance, please check if available at localhost:9200");
        }
    }
}
