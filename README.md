# Apache Flume customizations for developing a secure and universal database auditing platform

A highly scalable, secure and central repository that stores consolidated audit data and optionally listener, 
alert and OS log events generated by the database instances. This central platform will be used for reporting, 
alerting and security policy management. The reports will provide a holistic view of activity across all databases 
and will include compliance reports, activity reports and privilege reports. The alerting mechanism will 
detect and alert on abnormal activity, potential intrusion and much more. As audit data is vital record of 
activity, to protect this information the central repository will reside outside of existing databases and most 
likely in Hadoop eco-system.

## Developed components

Several implementations have been made for adapting Flume, both in the source and sink side.

* JDBCSource: a custom source which is able to collect data from database tables. It makes use of a ReliableJdbcEventReader which uses a JDBC driver for connecting to a database, so many types of databases are compatible. It provides a reliable way to read data from tables in order to avoid data loss and replicated events. This source produces JSONEvents (implements Flume Event interface), this events are deserialized as a JSON string.
* For some sinks, you may need to implement a custom parser for Flume Events:
    * JSONtoAvroParser: for Kite sink, this parser converts Flume Events which body is JSON into Avro records.
    * JSONtoElasticSearchEventSerializer: for Elasticsearch sink, this parser converts Flume Events which body is JSON into Elasticsearch XContentBuilder.

## Schema creation utility for Kite sink and JDBC source 

When using Kite as sink, previously you need to create a dataset. In order to create a Kite dataset, you should use the following command:

```
kite-dataset create <dataset_name> -s schema.avsc
```

This command receives an argument which points out a file (schema.avsc) containing the new dataset schema. You can infer the schema from a database table with an utility provided. An use case when this utility can be useful is when using ReliableJdbcAuditEventReader in the source.

Below command should be used for running this utility. This script is contained into scripts directory.

```
./infer-avro-schema-from-database.sh -c <Connection URL> -t <TABLE_NAME> -u <USERNSME> -p <PASSWORD> [-dc <DRIVER_FQCN>] [-catalog <CATALOG_NAME>] [-schema <SCHEMA_NAME>] [-help]
 -c <CONNECTION_URL>       URL for connecting to database
 -t <TABLE_NAME>           Table from which schema is inferred
 -u <USERNSME>             User to authenticate against database
 -p <PASSWORD>             User's password
 -dc <DRIVER_FQCN>         Fully qualified class name of JDBC driver (default: oracle.jdbc.driver.OracleDriver)
 -catalog <CATALOG_NAME>   Table catalog
 -schema <SCHEMA_NAME>     Table schema
 -help                     Print help
```

This script adds to classpath all JAR files contained in lib folder, there should be placed the JDBC driver.

You can redirect the output of previous command to a local file and then, use this file for creating a Kite datasest. 

```
sh infer-avro-schema-from-database.sh -c jdbc:oracle:thin:@itrac13108.cern.ch:10121:IMT -u <user> -p <password> -t ADMIN_EMP > schema.avsc
kite-dataset create ADMIN_EMP_DATASET -s schema.avsc
```

## Configuration

### AuditSource

In order to use AuditSource as source in your Flume agent, you need to specify the type of agent source as:

```
<agent_name>.sources.<source_name>.type = ch.cern.db.flume.source.JDBCSource 
```

Set number of events to be processed in every batch:
```
<agent_name>.sources.<source_name>.batch.size = 100
```

Minimun time in milliseconds a batch must last:
```
<agent_name>.sources.<source_name>.batch.minimumTime = 10000
```

### ReliableJdbcAuditEventReader

Find below all available configuration parameters:

```
<agent_name>.sources.<source_name>.reader.committingFile = committed_value.backup
<agent_name>.sources.<source_name>.reader.connectionDriver = oracle.jdbc.driver.OracleDriver
<agent_name>.sources.<source_name>.reader.connectionUrl = jdbc:oracle:oci:@
<agent_name>.sources.<source_name>.reader.username = sys as sysdba
<agent_name>.sources.<source_name>.reader.password = sys
<agent_name>.sources.<source_name>.reader.table = NULL
<agent_name>.sources.<source_name>.reader.table.columnToCommit = NULL
<agent_name>.sources.<source_name>.reader.table.columnToCommit.type = [TIMESTAMP (default)|NUMERIC|STRING]
<agent_name>.sources.<source_name>.reader.query = NULL
<agent_name>.sources.<source_name>.reader.query.path = NULL
```

Default values are written, parameters with NULL has not default value. Most configuration parameters do not require any further explanation. However, some of then need to be explained.

Since it is a reliable reader, it requires one column of the table to be used for committing its value. Column to use for this purpose is configured with ".columnToCommit" parameter. You would need to specify the type of this column in order to build the query properly.

".table" or ".query" parameter must be configured. ".columnToCommit" is always required.

In case the query is not built properly or you want to use a custom one, you can use ".query" parameter (or ".query.path" for loading the query from a file). In that case ".table" and "columnToCommit.type" parameters are ignored. You should use the following syntax:

```
SELECT * FROM table_name [WHERE column_name > '{$committed_vale}'] ORDER BY column_name
```

Some tips:
* ORDER BY clause is strongly recommended to be used since last value from "column to commit" will be used in further queries to get only last rows.
* If no value has been committed yet, part of the query between [] is removed. This is the case when we first start Flume.
* All occurrences of :committed_value will be replaced by last committed value.
* :committed_value should be between [], but if there will be always a committed value you do not need to place them.

Custom query example:

```
reader.query = SELECT * FROM UNIFIED_AUDIT_TRAIL [WHERE EVENT_TIMESTAMP > TIMESTAMP '{$committed_value}'] ORDER BY EVENT_TIMESTAMP
```

Query to be executed when a value has not been committed yet:

```
SELECT * FROM UNIFIED_AUDIT_TRAIL ORDER BY EVENT_TIMESTAMP
```

As soon as a value has been committed, query will be like:

```
SELECT * FROM UNIFIED_AUDIT_TRAIL WHERE EVENT_TIMESTAMP > TIMESTAMP '2013-11-08 12:11:31.123123 Europe/Zurich' ORDER BY EVENT_TIMESTAMP
```

### JSONtoAvroParser and JSONtoElasticSearchEventSerializer

They do not have any configuration parameters.








 

