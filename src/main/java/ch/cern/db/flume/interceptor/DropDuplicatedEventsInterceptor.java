/**
 * Copyright (C) 2016, CERN
 * This software is distributed under the terms of the GNU General Public
 * Licence version 3 (GPL Version 3), copied verbatim in the file "LICENSE".
 * In applying this license, CERN does not waive the privileges and immunities
 * granted to it by virtue of its status as Intergovernmental Organization
 * or submit itself to any jurisdiction.
 */

package ch.cern.db.flume.interceptor;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.cern.db.flume.source.DropDuplicatedEventsProcessor;
import ch.cern.db.utils.SizeLimitedHashSet;

/**
 * Compare current event with last events to check it was already processed
 * 
 * @deprecated duplicated event processors as {@link DropDuplicatedEventsProcessor} should
 * be used in sources for dropping duplicated events. This interceptor will drop events in case
 * transaction to channel fails or agent is restarted.
 */
@Deprecated
public class DropDuplicatedEventsInterceptor implements Interceptor {

	private static final Logger LOG = LoggerFactory.getLogger(DropDuplicatedEventsInterceptor.class);
	
	private boolean checkHeaders;
	private boolean checkBody;
	
	private int size;
	
	private SizeLimitedHashSet<Integer> last_hashes;
	
	private DropDuplicatedEventsInterceptor(Context context){
		checkHeaders = context.getBoolean("headers", true);
		checkBody = context.getBoolean("body", true);
		size = context.getInteger("size", 1000);
	}
	
	@Override
	public void initialize() {
		last_hashes = new SizeLimitedHashSet<Integer>(size);
		
		LOG.info("Intialized with size="+size+", headers="+checkHeaders+", body="+checkBody);
	}

	@Override
	public Event intercept(Event event) {
		int event_hash = hashCode(event);
		
		if(last_hashes.contains(event_hash))
			return null;
		
		last_hashes.add(event_hash);
		return event;
	}

	private int hashCode(Event event) {
		int headers_hash = 0;
		if(checkHeaders)
			headers_hash = event.getHeaders().hashCode();
		
		int body_hash = 0;
		if(checkBody)
			body_hash = Arrays.hashCode(event.getBody());
		
		if(checkHeaders && checkBody)
			return headers_hash ^ body_hash;
		if (checkHeaders && !checkBody)
			return headers_hash;
		if (!checkHeaders && checkBody)
			return body_hash;
		//else all events will be removed due to hash will be always 0
			return 0;
	}

	@Override
	public List<Event> intercept(List<Event> events) {
		LinkedList<Event> intercepted_events = new LinkedList<Event>();
		
		for (Event event : events) {
			Event intercepted_event = intercept(event);
			if(intercepted_event != null)
				intercepted_events.add(event);
		}
		
		return intercepted_events;
	}

	@Override
	public void close() {
		last_hashes.clear();
	}

	/**
	 * Builder which builds new instance of this class
	 */
	public static class Builder implements Interceptor.Builder {

		private Context context;
		
		@Override
		public void configure(Context context) {
			this.context = context;
		}

		@Override
		public Interceptor build() {
			return new DropDuplicatedEventsInterceptor(context);
		}

	}
	
}