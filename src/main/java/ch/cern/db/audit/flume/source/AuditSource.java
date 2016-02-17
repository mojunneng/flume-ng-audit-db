package ch.cern.db.audit.flume.source;

import java.io.IOException;
import java.util.List;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.cern.db.audit.flume.source.reader.ReliableEventReader;
import ch.cern.db.audit.flume.source.reader.ReliableEventReader.Builder;
import ch.cern.db.audit.flume.source.reader.ReliableEventReaderBuilderFactory;

public class AuditSource extends AbstractSource implements Configurable, PollableSource {

	private static final Logger LOG = LoggerFactory.getLogger(AuditSource.class);

	private static final int BATCH_SIZE = 100;

	private static final long MINIMUM_BATCH_TIME = 10000;

	private static final String READER_DEFAULT = ReliableEventReaderBuilderFactory.Types.ORACLE.toString();
	private static final String READER_PARAM = "reader";

	private ReliableEventReader reader;
	
	@Override
	public void configure(Context context) {
		String reader_config = context.getString(READER_PARAM, READER_DEFAULT);
		Builder builder = ReliableEventReaderBuilderFactory.newInstance(reader_config);
        builder.configure(context);
		reader = builder.build();
	}
	
	@Override
	public Status process() throws EventDeliveryException {
		Status status = null;
		
		long batchStartTime = System.currentTimeMillis();
		
		try{
			List<Event> events = reader.readEvents(BATCH_SIZE);
			
			getChannelProcessor().processEventBatch(events);
			
			reader.commit();
			
			status = Status.READY;
		}catch(Throwable e){
			status = Status.BACKOFF;
			
			LOG.error(e.getMessage(), e);
			throw new EventDeliveryException(e);
		}
		
		sleep(batchStartTime);
		
		return status;
	}

	private void sleep(long batchStartTime) {
		long elapsedTime = System.currentTimeMillis() - batchStartTime;
		
		if(elapsedTime <= MINIMUM_BATCH_TIME){
			try {
				Thread.sleep(MINIMUM_BATCH_TIME - elapsedTime);
			} catch (InterruptedException e) {}
		}
	}

	@Override
	public synchronized void stop() {
		try {
			reader.close();
		} catch (IOException e){}
	}

	@Override
	public long getBackOffSleepIncrement() {
		return 0;
	}

	@Override
	public long getMaxBackOffSleepInterval() {
		return 0;
	}

}
