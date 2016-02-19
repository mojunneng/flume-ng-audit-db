package ch.cern.db.audit.flume.source.reader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("resource")
public class ReliableJdbcAuditEventReaderTests {
	
	String connection_url = "jdbc:hsqldb:mem:aname";
	Connection connection = null;
	
	@Before
	public void setup(){
		try {
			connection = DriverManager.getConnection(connection_url, "sa", "");
			
			Statement statement = connection.createStatement();
			statement.execute("DROP TABLE IF EXISTS audit_data_table;");
			statement.execute("CREATE TABLE audit_data_table (id INTEGER, return_code BIGINT, name VARCHAR(20));");
			statement.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
	}
	
	@Test
	public void eventsFromDatabase(){
		
		Context context = new Context();
		context.put(ReliableJdbcAuditEventReader.CONNECTION_DRIVER_PARAM, "org.hsqldb.jdbc.JDBCDriver");
		context.put(ReliableJdbcAuditEventReader.CONNECTION_URL_PARAM, connection_url);
		context.put(ReliableJdbcAuditEventReader.USERNAME_PARAM, "SA");
		context.put(ReliableJdbcAuditEventReader.PASSWORD_PARAM, "");
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, " audit_data_table");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "ID");
		ReliableJdbcAuditEventReader reader = new ReliableJdbcAuditEventReader(context);
		
		try {
			Event event = reader.readEvent();
			Assert.assertNull(event);
			
			Statement statement = connection.createStatement();
			statement.execute("INSERT INTO audit_data_table VALUES 2, 48, 'name2';");
			event = reader.readEvent();
			Assert.assertNotNull(event);
			Assert.assertEquals("{\"ID\":2,\"RETURN_CODE\":48,\"NAME\":\"name2\"}", new String(event.getBody()));
			event = reader.readEvent();
			Assert.assertNull(event);
			
			statement = connection.createStatement();
			statement.execute("INSERT INTO audit_data_table VALUES 1, 48, 'name1';");
			statement.execute("INSERT INTO audit_data_table VALUES 3, 48, 'name3';");
			statement.close();
			event = reader.readEvent();
			Assert.assertNotNull(event);
			Assert.assertEquals("{\"ID\":1,\"RETURN_CODE\":48,\"NAME\":\"name1\"}", new String(event.getBody()));
			event = reader.readEvent();
			Assert.assertNotNull(event);
			Assert.assertEquals("{\"ID\":3,\"RETURN_CODE\":48,\"NAME\":\"name3\"}", new String(event.getBody()));
			event = reader.readEvent();
			Assert.assertNull(event);
			
		} catch (IOException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	@Test
	public void createCommitFile(){
		Context context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		ReliableJdbcAuditEventReader reader = new ReliableJdbcAuditEventReader(context);
		
		String timestamp = "2016-02-09 09:34:51.244507 Europe/Zurich";
		
		reader.last_value = timestamp ;
		try {
			reader.commit();
		} catch (IOException e) {
			Assert.fail(e.getMessage());
		}
		
		try {
			FileReader in = new FileReader(ReliableJdbcAuditEventReader.COMMITTING_FILE_PATH_DEFAULT);
			char [] in_chars = new char[50];
		    in.read(in_chars);
			in.close();
			
			String timestamp_from_file = new String(in_chars).trim();
			
			Assert.assertEquals(timestamp, reader.committed_value);
			Assert.assertEquals(timestamp, timestamp_from_file);
		} catch (IOException e) {
			Assert.fail(e.getMessage());
		}
	}
	
	@Test
	public void readCommitFile(){
		
		String timestamp = "2016-02-09 09:34:51.244507 Europe/Zurich";
		
		try {
			FileWriter out = new FileWriter(ReliableJdbcAuditEventReader.COMMITTING_FILE_PATH_DEFAULT, false);
			out.write(timestamp);
			out.close();
		} catch (IOException e) {
			Assert.fail();
		}
		
		Context context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		ReliableJdbcAuditEventReader reader = new ReliableJdbcAuditEventReader(context);
		
		Assert.assertEquals(timestamp, reader.committed_value);
	}
	
	@Test
	public void readEmptyCommitFile(){
		
		//It will create the file
		Context context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		ReliableJdbcAuditEventReader reader = new ReliableJdbcAuditEventReader(context);
		Assert.assertNull(reader.committed_value);
		
		//It will read an empty file
		reader = new ReliableJdbcAuditEventReader(context);
		Assert.assertNull(reader.committed_value);
		
		String timestamp = "2016-02-09 09:34:51.244507 Europe/Zurich";
		reader.last_value = timestamp;
		try {
			reader.commit();
		} catch (IOException e) {
			Assert.fail(e.getMessage());
		}
		
		try {
			FileReader in = new FileReader(ReliableJdbcAuditEventReader.COMMITTING_FILE_PATH_DEFAULT);
			char [] in_chars = new char[50];
		    in.read(in_chars);
			in.close();
			
			String timestamp_from_file = new String(in_chars).trim();
			
			Assert.assertEquals(timestamp, reader.committed_value);
			Assert.assertEquals(timestamp, timestamp_from_file);
		} catch (IOException e) {
			Assert.fail(e.getMessage());
		}
	}
	
	@Test
	public void queryCreation(){
		
		Context context = new Context();
		context.put(ReliableJdbcAuditEventReader.QUERY_PARAM, "new query");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		ReliableJdbcAuditEventReader reader = new ReliableJdbcAuditEventReader(context);
		
		String result = reader.createQuery(null);
		Assert.assertEquals(result, "new query");
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.QUERY_PARAM, 
				"SELECT * FROM table_name [WHERE column_name > '{$committed_value}'] ORDER BY column_name");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery(null);
		Assert.assertEquals(result, "SELECT * FROM table_name  ORDER BY column_name");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.QUERY_PARAM, 
				"SELECT * FROM table_name [WHERE column_name > '{$committed_value}'] ORDER BY column_name");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery("12345");
		Assert.assertEquals(result, "SELECT * FROM table_name  WHERE column_name > '12345'  ORDER BY column_name");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column");
		context.put(ReliableJdbcAuditEventReader.QUERY_PARAM, "new query");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery("value");
		Assert.assertEquals(result, "new query");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table_name1");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column_name1");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery(null);
		Assert.assertEquals(result, "SELECT * FROM table_name1 ORDER BY column_name1");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table_name2");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column_name2");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery("2016-02-09 09:34:51.244");
		Assert.assertEquals(result, "SELECT * FROM table_name2 "
				+ "WHERE column_name2 > TIMESTAMP '2016-02-09 09:34:51.244' "
				+ "ORDER BY column_name2");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table_name2");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column_name2");
		context.put(ReliableJdbcAuditEventReader.TYPE_COLUMN_TO_COMMIT_PARAM, "timestamp");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery("2016-02-09 09:34:51.244");
		Assert.assertEquals(result, "SELECT * FROM table_name2 "
				+ "WHERE column_name2 > TIMESTAMP '2016-02-09 09:34:51.244' "
				+ "ORDER BY column_name2");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table_name3");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column_name3");
		context.put(ReliableJdbcAuditEventReader.TYPE_COLUMN_TO_COMMIT_PARAM, "numeric");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery("244");
		Assert.assertEquals(result, "SELECT * FROM table_name3 "
				+ "WHERE column_name3 > 244 "
				+ "ORDER BY column_name3");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table_name4");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column_name4");
		context.put(ReliableJdbcAuditEventReader.TYPE_COLUMN_TO_COMMIT_PARAM, "string");
		reader = new ReliableJdbcAuditEventReader(context);
		
		result = reader.createQuery("string4");
		Assert.assertEquals(result, "SELECT * FROM table_name4 "
				+ "WHERE column_name4 > \'string4\' "
				+ "ORDER BY column_name4");
		
		
		context = new Context();
		context.put(ReliableJdbcAuditEventReader.TABLE_NAME_PARAM, "table_name4");
		context.put(ReliableJdbcAuditEventReader.COLUMN_TO_COMMIT_PARAM, "column_name4");
		context.put(ReliableJdbcAuditEventReader.TYPE_COLUMN_TO_COMMIT_PARAM, "does_not_exist");
		
		try{
			reader = new ReliableJdbcAuditEventReader(context);
			Assert.fail();
		}catch(FlumeException e){
		}
	}

	@After
	public void cleanUp(){
		new File(ReliableJdbcAuditEventReader.COMMITTING_FILE_PATH_DEFAULT).delete();
		
		try {
			connection.close();
		} catch (SQLException e) {}
	}
}