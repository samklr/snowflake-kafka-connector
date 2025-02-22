package com.snowflake.kafka.connector.internal.streaming;

import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.internal.SnowflakeErrors;
import com.snowflake.kafka.connector.internal.SnowflakeSinkService;
import com.snowflake.kafka.connector.internal.SnowflakeSinkServiceFactory;
import com.snowflake.kafka.connector.internal.TestUtils;
import com.snowflake.kafka.connector.records.SnowflakeConverter;
import com.snowflake.kafka.connector.records.SnowflakeJsonConverter;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;

public class SnowflakeSinkServiceV2IT {

  private SnowflakeConnectionService conn = TestUtils.getConnectionServiceForStreamingIngest();
  private String table = TestUtils.randomTableName();
  private int partition = 0;
  private String topic = "test";
  private static ObjectMapper MAPPER = new ObjectMapper();

  private static final String JSON_WITHOUT_SCHEMA = "{\"userid\": \"User_1\"}";

  private static final String JSON_WITH_SCHEMA =
      ""
          + "{\n"
          + "  \"schema\": {\n"
          + "    \"type\": \"struct\",\n"
          + "    \"fields\": [\n"
          + "      {\n"
          + "        \"type\": \"string\",\n"
          + "        \"optional\": false,\n"
          + "        \"field\": \"regionid\"\n"
          + "      },\n"
          + "      {\n"
          + "        \"type\": \"string\",\n"
          + "        \"optional\": false,\n"
          + "        \"field\": \"gender\"\n"
          + "      }\n"
          + "    ],\n"
          + "    \"optional\": false,\n"
          + "    \"name\": \"ksql.users\"\n"
          + "  },\n"
          + "  \"payload\": {\n"
          + "    \"regionid\": \"Region_5\",\n"
          + "    \"gender\": \"MALE\"\n"
          + "  }\n"
          + "}";

  @After
  public void afterEach() {
    TestUtils.dropTableStreaming(table);
  }

  @Ignore
  @Test
  public void testSinkServiceV2Builder() {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .build();

    assert service instanceof SnowflakeSinkServiceV2;

    // connection test
    assert TestUtils.assertError(
        SnowflakeErrors.ERROR_5010,
        () ->
            SnowflakeSinkServiceFactory.builder(
                    null, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
                .build());
    assert TestUtils.assertError(
        SnowflakeErrors.ERROR_5010,
        () -> {
          SnowflakeConnectionService conn = TestUtils.getConnectionServiceForStreamingIngest();
          conn.close();
          SnowflakeSinkServiceFactory.builder(
                  conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
              .build();
        });
  }

  @Ignore
  @Test
  public void testChannelCloseIngestion() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .addTask(table, topic, partition) // Internally calls startTask
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
    long offset = 0;

    SinkRecord record1 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    // Lets close the service
    // Closing a partition == closing a channel
    service.close(Collections.singletonList(new TopicPartition(topic, partition)));

    // Lets insert a record when partition was closed.
    // It should auto create the channel
    service.insert(record1);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 20, 5);

    service.closeAll();
  }

  @Ignore
  @Test
  public void testStreamingIngestion() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .addTask(table, topic, partition) // Internally calls startTask
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
    long offset = 0;

    SinkRecord record1 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    service.insert(record1);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 20, 5);

    // insert another offset and check what we committed
    offset += 1;
    SinkRecord record2 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);
    offset += 1;
    SinkRecord record3 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    service.insert(Arrays.asList(record2, record3));
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 3, 20, 5);

    service.closeAll();
  }

  @Ignore
  @Test
  public void testNativeJsonInputIngestion() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // json without schema
    JsonConverter converter = new JsonConverter();
    HashMap<String, String> converterConfig = new HashMap<String, String>();
    converterConfig.put("schemas.enable", "false");
    converter.configure(converterConfig, false);
    SchemaAndValue noSchemaInputValue =
        converter.toConnectData(topic, JSON_WITHOUT_SCHEMA.getBytes(StandardCharsets.UTF_8));

    converter = new JsonConverter();
    converterConfig = new HashMap<>();
    converterConfig.put("schemas.enable", "false");
    converter.configure(converterConfig, true);
    SchemaAndValue noSchemaInputKey =
        converter.toConnectData(topic, JSON_WITHOUT_SCHEMA.getBytes(StandardCharsets.UTF_8));

    // json with schema
    converter = new JsonConverter();
    converterConfig = new HashMap<>();
    converterConfig.put("schemas.enable", "true");
    converter.configure(converterConfig, false);
    SchemaAndValue schemaInputValue =
        converter.toConnectData(topic, JSON_WITH_SCHEMA.getBytes(StandardCharsets.UTF_8));

    converter = new JsonConverter();
    converterConfig = new HashMap<>();
    converterConfig.put("schemas.enable", "true");
    converter.configure(converterConfig, true);
    SchemaAndValue schemaInputKey =
        converter.toConnectData(topic, JSON_WITH_SCHEMA.getBytes(StandardCharsets.UTF_8));

    long startOffset = 0;
    long endOffset = 3;

    SinkRecord noSchemaRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            noSchemaInputValue.schema(),
            noSchemaInputValue.value(),
            startOffset);
    SinkRecord schemaRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            schemaInputValue.schema(),
            schemaInputValue.value(),
            startOffset + 1);

    SinkRecord noSchemaRecordKey =
        new SinkRecord(
            topic,
            partition,
            noSchemaInputKey.schema(),
            noSchemaInputKey.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 2);
    SinkRecord schemaRecordKey =
        new SinkRecord(
            topic,
            partition,
            schemaInputKey.schema(),
            schemaInputKey.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 3);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .addTask(table, topic, partition) // Internally calls startTask
            .build();

    service.insert(noSchemaRecordValue);
    service.insert(schemaRecordValue);

    service.insert(noSchemaRecordKey);
    service.insert(schemaRecordKey);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == endOffset + 1, 20, 5);

    service.closeAll();
  }

  @Ignore
  @Test
  public void testNativeAvroInputIngestion() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    // avro
    SchemaBuilder schemaBuilder =
        SchemaBuilder.struct()
            .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16", Schema.INT16_SCHEMA)
            .field("int32", Schema.INT32_SCHEMA)
            .field("int64", Schema.INT64_SCHEMA)
            .field("float32", Schema.FLOAT32_SCHEMA)
            .field("float64", Schema.FLOAT64_SCHEMA)
            .field("int8Min", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16Min", Schema.INT16_SCHEMA)
            .field("int32Min", Schema.INT32_SCHEMA)
            .field("int64Min", Schema.INT64_SCHEMA)
            .field("float32Min", Schema.FLOAT32_SCHEMA)
            .field("float64Min", Schema.FLOAT64_SCHEMA)
            .field("int8Max", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16Max", Schema.INT16_SCHEMA)
            .field("int32Max", Schema.INT32_SCHEMA)
            .field("int64Max", Schema.INT64_SCHEMA)
            .field("float32Max", Schema.FLOAT32_SCHEMA)
            .field("float64Max", Schema.FLOAT64_SCHEMA)
            .field("float64HighPrecision", Schema.FLOAT64_SCHEMA)
            .field("float64TenDigits", Schema.FLOAT64_SCHEMA)
            .field("float64BigDigits", Schema.FLOAT64_SCHEMA)
            .field("boolean", Schema.BOOLEAN_SCHEMA)
            .field("string", Schema.STRING_SCHEMA)
            .field("bytes", Schema.BYTES_SCHEMA)
            .field("bytesReadOnly", Schema.BYTES_SCHEMA)
            .field("int16Optional", Schema.OPTIONAL_INT16_SCHEMA)
            .field("int32Optional", Schema.OPTIONAL_INT32_SCHEMA)
            .field("int64Optional", Schema.OPTIONAL_INT64_SCHEMA)
            .field("float32Optional", Schema.OPTIONAL_FLOAT32_SCHEMA)
            .field("float64Optional", Schema.OPTIONAL_FLOAT64_SCHEMA)
            .field("booleanOptional", Schema.OPTIONAL_BOOLEAN_SCHEMA)
            .field("stringOptional", Schema.OPTIONAL_STRING_SCHEMA)
            .field("bytesOptional", Schema.OPTIONAL_BYTES_SCHEMA)
            .field("array", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
            .field("map", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build())
            .field(
                "int8Optional",
                SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field(
                "mapNonStringKeys",
                SchemaBuilder.map(Schema.INT32_SCHEMA, Schema.INT32_SCHEMA).build())
            .field(
                "mapArrayMapInt",
                SchemaBuilder.map(
                        Schema.STRING_SCHEMA,
                        SchemaBuilder.array(
                                SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA)
                                    .build())
                            .build())
                    .build());
    Struct original =
        new Struct(schemaBuilder.build())
            .put("int8", (byte) 12)
            .put("int16", (short) 12)
            .put("int32", 12)
            .put("int64", 12L)
            .put("float32", 12.2f)
            .put("float64", 12.2)
            .put("int8Min", Byte.MIN_VALUE)
            .put("int16Min", Short.MIN_VALUE)
            .put("int32Min", Integer.MIN_VALUE)
            .put("int64Min", Long.MIN_VALUE)
            .put("float32Min", Float.MIN_VALUE)
            .put("float64Min", Double.MIN_VALUE)
            .put("int8Max", Byte.MAX_VALUE)
            .put("int16Max", Short.MAX_VALUE)
            .put("int32Max", Integer.MAX_VALUE)
            .put("int64Max", Long.MAX_VALUE)
            .put("float32Max", Float.MAX_VALUE)
            .put("float64Max", Double.MAX_VALUE)
            .put("float64HighPrecision", 2312.4200000000001d)
            .put("float64TenDigits", 1.0d / 3.0d)
            .put("float64BigDigits", 2312.42321432655123456d)
            .put("boolean", true)
            .put("string", "foo")
            .put("bytes", ByteBuffer.wrap("foo".getBytes()))
            .put("bytesReadOnly", ByteBuffer.wrap("foo".getBytes()).asReadOnlyBuffer())
            .put("array", Arrays.asList("a", "b", "c"))
            .put("map", Collections.singletonMap("field", 1))
            .put("mapNonStringKeys", Collections.singletonMap(1, 1))
            .put(
                "mapArrayMapInt",
                Collections.singletonMap(
                    "field",
                    Arrays.asList(
                        Collections.singletonMap("field", 1),
                        Collections.singletonMap("field", 1))));

    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData(topic, original.schema(), original);
    SchemaAndValue avroInputValue = avroConverter.toConnectData(topic, converted);

    avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), true);
    converted = avroConverter.fromConnectData(topic, original.schema(), original);
    SchemaAndValue avroInputKey = avroConverter.toConnectData(topic, converted);

    long startOffset = 0;
    long endOffset = 2;

    SinkRecord avroRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            avroInputValue.schema(),
            avroInputValue.value(),
            startOffset);

    SinkRecord avroRecordKey =
        new SinkRecord(
            topic,
            partition,
            avroInputKey.schema(),
            avroInputKey.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 1);

    SinkRecord avroRecordKeyValue =
        new SinkRecord(
            topic,
            partition,
            avroInputKey.schema(),
            avroInputKey.value(),
            avroInputKey.schema(),
            avroInputKey.value(),
            startOffset + 2);

    conn.createTable(table);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .addTask(table, topic, partition)
            .build();

    service.insert(avroRecordValue);
    service.insert(avroRecordKey);
    service.insert(avroRecordKeyValue);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == endOffset + 1, 20, 5);

    service.closeAll();
  }

  // TODO: Check content in DLQ if records are broken SNOW-451197
  @Ignore
  @Test
  public void testBrokenIngestion() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // Mismatched schema and value
    SchemaAndValue brokenInputValue = new SchemaAndValue(Schema.INT32_SCHEMA, "error");

    long startOffset = 0;

    SinkRecord brokenValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            brokenInputValue.schema(),
            brokenInputValue.value(),
            startOffset);

    SinkRecord brokenKey =
        new SinkRecord(
            topic,
            partition,
            brokenInputValue.schema(),
            brokenInputValue.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 1);

    SinkRecord brokenKeyValue =
        new SinkRecord(
            topic,
            partition,
            brokenInputValue.schema(),
            brokenInputValue.value(),
            brokenInputValue.schema(),
            brokenInputValue.value(),
            startOffset + 2);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .addTask(table, topic, partition)
            .build();

    service.insert(brokenValue);
    service.insert(brokenKey);
    service.insert(brokenKeyValue);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 0, 20, 5);
  }

  @Ignore
  @Test
  public void testBrokenRecordIngestionFollowedUpByValidRecord() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // Mismatched schema and value
    SchemaAndValue brokenInputValue = new SchemaAndValue(Schema.INT32_SCHEMA, "error");
    SchemaAndValue correctInputValue = new SchemaAndValue(Schema.STRING_SCHEMA, "correct");

    long recordCount = 1;

    SinkRecord brokenValue =
        new SinkRecord(
            topic, partition, null, null, brokenInputValue.schema(), brokenInputValue.value(), 0);

    SinkRecord brokenKey =
        new SinkRecord(
            topic, partition, brokenInputValue.schema(), brokenInputValue.value(), null, null, 1);

    SinkRecord correctValue =
        new SinkRecord(
            topic, partition, null, null, correctInputValue.schema(), correctInputValue.value(), 2);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(recordCount)
            .addTask(table, topic, partition)
            .build();

    service.insert(brokenValue);
    service.insert(brokenKey);
    service.insert(correctValue);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 3, 20, 5);

    service.closeAll();
  }
}
