package ch.cern.db.audit.flume.source.reader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import oracle.jdbc.driver.OracleConnection;
import oracle.jdbc.pool.OracleDataSource;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.cern.db.audit.flume.AuditEvent;
import ch.cern.db.audit.flume.source.deserializer.AuditEventDeserializer;
import ch.cern.db.audit.flume.source.deserializer.AuditEventDeserializerBuilderFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

public class ReliableOracleAuditEventReader implements ReliableEventReader {

	private static final Logger LOG = LoggerFactory.getLogger(ReliableOracleAuditEventReader.class);
	
	private static final String CONNECTION_URL = "jdbc:oracle:oci:@";

	public static final String COMMITTING_FILE_PATH = "committed_value.backup";

	private static final String DESERIALIZER_PARAM = "deserializer";
	private static final String DESERIALIZER_DEFAULT = AuditEventDeserializerBuilderFactory.Types.JSON.toString();
	
	private OracleDataSource dataSource = null;
	private Connection connection = null;
	private ResultSet resultSet = null;
	private Statement statement = null;
	private String tableName = null;
	private String columnToCommit = null;
	private String configuredQuery = null;
	
	protected String last_value = null;
	protected String committed_value = null;
	private File committing_file = null;
	private Integer numberOfColumnToCommit = null;
	
	private int columnCount;

	private ArrayList<String> columnNames;
	private ArrayList<Integer> columnTypes;
	
	private AuditEventDeserializer deserializer;
	
	@VisibleForTesting
	protected ReliableOracleAuditEventReader(){
		committing_file = new File(COMMITTING_FILE_PATH);
		loadLastCommittedValue();
	}
	
	public ReliableOracleAuditEventReader(Context context) {
		tableName = "UNIFIED_AUDIT_TRAIL";
		columnToCommit = "EVENT_TIMESTAMP";
		configuredQuery = null;
		
		if(configuredQuery == null){
			Preconditions.checkNotNull(tableName, "Table name needs to be configured");
			Preconditions.checkNotNull(columnToCommit, "Column to commit needs to be configured");
		}
		
		Properties prop = new Properties();
		prop.put(OracleConnection.CONNECTION_PROPERTY_USER_NAME, "sys");
		prop.put(OracleConnection.CONNECTION_PROPERTY_PASSWORD, "sys");
		prop.put(OracleConnection.CONNECTION_PROPERTY_INTERNAL_LOGON, "sysdba");
		
		try {
			dataSource = new OracleDataSource();
			dataSource.setConnectionProperties(prop);
			dataSource.setURL(CONNECTION_URL);
		} catch (SQLException e) {
			LOG.error(e.getMessage(), e);
		}
		
		getColumnMetadata();
		
		String des_config = context.getString(DESERIALIZER_PARAM, DESERIALIZER_DEFAULT);
		AuditEventDeserializer.Builder builder = AuditEventDeserializerBuilderFactory.newInstance(des_config);
        builder.configure(context);
		this.deserializer = builder.build();
		
		committing_file = new File(COMMITTING_FILE_PATH);
		
		loadLastCommittedValue();
	}

	protected void getColumnMetadata() {
		try {
			connect();
			
			Statement statement = connection.createStatement();
			String query = "SELECT * "
					+ "FROM " + tableName
					+ " WHERE ROWNUM < 1";
			ResultSet resultSet = statement.executeQuery(query);
			ResultSetMetaData metadata = resultSet.getMetaData();
			columnCount = metadata.getColumnCount();
			
			columnNames = new ArrayList<String>();	
			columnTypes = new ArrayList<Integer>();	
			for (int i = 1; i <= columnCount; i++){
				columnNames.add(metadata.getColumnName(i));
				columnTypes.add(metadata.getColumnType(i));
				
				if(metadata.getColumnName(i).equals(columnToCommit)){
					numberOfColumnToCommit = i;
				}
			}
			
			if(numberOfColumnToCommit == null){
				throw new FlumeException("Name of column to commit was " + columnCount +
						" but in table " + tableName + " there is no column with this name");
			}
		} catch (SQLException e) {
			LOG.error(e.getMessage(), e);
			
			throw new FlumeException(e);
		}
	}

	private void loadLastCommittedValue() {
		try {
			if(committing_file.exists()){
				FileReader in = new FileReader(committing_file);
				char [] in_chars = new char[60];
			    in.read(in_chars);
				in.close();
				String value_from_file = new String(in_chars).trim();
				
				if(value_from_file.length() > 1){
					committed_value = value_from_file;
					
					LOG.info("Last value loaded from file: " + committed_value);
				}else{
					LOG.info("File for loading last value is empty");
				}
			}else{
				committing_file.createNewFile();
				
				LOG.info("File for storing last commited value have been created: " +
						committing_file.getAbsolutePath());
			}
		} catch (IOException e) {
			throw new FlumeException(e);
		}
	}

	@Override
	public Event readEvent() throws IOException {
		try {
			if(resultSet == null)
				runQuery();
			
			if(resultSet != null && !resultSet.isClosed() && resultSet.next()){
				AuditEvent event = new AuditEvent();
				
				for (int i = 1; i <= columnCount; i++) {										
					String name = columnNames.get(i - 1);
					
					switch (columnTypes.get(i - 1)) {
					case java.sql.Types.SMALLINT:
					case java.sql.Types.TINYINT:
					case java.sql.Types.INTEGER:
					case java.sql.Types.BIGINT:
						event.addField(name, resultSet.getInt(i));
						break;
					case java.sql.Types.BOOLEAN:
						event.addField(name, resultSet.getBoolean(i));
						break;
					case java.sql.Types.NUMERIC:
					case java.sql.Types.DOUBLE:
					case java.sql.Types.FLOAT:
						event.addField(name, resultSet.getDouble(i));
						break;
					case java.sql.Types.TIMESTAMP:
					case -102: //TIMESTAMP(6) WITH LOCAL TIME ZONE
						String ts = resultSet.getTimestamp(i).toString();
						ts = ts.substring(0, 23).replace(" ", "T");
						event.addField(name, ts);
						break;
					default:
						event.addField(name, resultSet.getString(i));
						break;
					}
				}				

				last_value = resultSet.getString(numberOfColumnToCommit);
				
				return deserializer.process(event);
			}else{
				resultSet = null;
				
				return null;
			}
		} catch (SQLException e) {
			throw new IOException(e);
		}
	}

	private void runQuery() throws SQLException {	
		if(statement != null)
			statement.close();
		
		connect();
		
		statement = connection.createStatement();
		
		String query = createQuery(configuredQuery,
				tableName,
				columnToCommit,
				columnTypes.get(numberOfColumnToCommit - 1),
				committed_value);
		
		resultSet = statement.executeQuery(query);
		
		LOG.info("Executing query: " + query);
	}

	protected String createQuery(String configuredQuery, 
			String tableName, 
			String columnToCommit, 
			Integer typeCommitColumn, 
			String committedValue) {
		
		if(configuredQuery != null)
			return configuredQuery;
		
		String query = "SELECT * FROM " + tableName;
		
		if(committedValue != null){
			query = query.concat(" WHERE " + columnToCommit + " > ");
		
			switch (typeCommitColumn) {
			case java.sql.Types.BOOLEAN:
			case java.sql.Types.SMALLINT:
			case java.sql.Types.TINYINT:
			case java.sql.Types.INTEGER:
			case java.sql.Types.BIGINT:
			case java.sql.Types.NUMERIC:
			case java.sql.Types.DOUBLE:
			case java.sql.Types.FLOAT:
				query = query.concat(committedValue);
				break;
			case java.sql.Types.TIMESTAMP:
			case -102: //TIMESTAMP(6) WITH LOCAL TIME ZONE
				query = query.concat("TIMESTAMP \'" + committedValue + "\'");
				break;
			default:
				query = query.concat("\'" + committedValue + "\'");
				break;
			}
		}
		
		query = query.concat(" ORDER BY " + columnToCommit);
		
		return query;
	}

	private void connect() throws SQLException{
		try {
			if(connection == null || connection.isClosed())
				connection = dataSource.getConnection();
			
		} catch (SQLException e) {
			LOG.error(e.getMessage(), e);
			throw e;
		}
	}
	
	@Override
	public List<Event> readEvents(int numberOfEventToRead) throws IOException {
		LinkedList<Event> events = new LinkedList<Event>();
		
		for (int i = 0; i < numberOfEventToRead; i++){
			Event event = readEvent();
			
			if(event != null)
				events.add(event);
			else{
				LOG.info("Number of events returned: " + events.size());
				return events;
			}
		}
		
		LOG.info("Number of events returned: " + events.size());
		return events;
	}

	@Override
	public void commit() throws IOException {
		if(last_value == null)
			return;
		
		committed_value = last_value;
		
		FileWriter out = new FileWriter(committing_file, false);
		out.write(committed_value);
		out.close();
		
		last_value = null;
	}

	@Override
	public void close() throws IOException {
		try {
			connection.close();
			statement.close();
		} catch (Throwable e) {
		}
	}

	public static class Builder implements ReliableEventReader.Builder {

		private Context context;
		
		@Override
		public void configure(Context context) {
			this.context = context;
		}

		@Override
		public ReliableOracleAuditEventReader build() {
			return new ReliableOracleAuditEventReader(context);
		}
		
	}
}
