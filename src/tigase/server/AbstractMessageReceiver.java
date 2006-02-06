/*  Package Jabber Server
 *  Copyright (C) 2001, 2002, 2003, 2004, 2005
 *  "Artur Hefczyc" <kobit@users.sourceforge.net>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software Foundation,
 *  Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;
import tigase.annotations.TODO;
import tigase.conf.Configurable;
import tigase.stats.StatRecord;
import tigase.stats.StatisticType;
import tigase.stats.StatisticsContainer;
import tigase.util.JID;

/**
 * Describe class AbstractMessageReceiver here.
 *
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@gmail.com">Artur Hefczyc</a>
 * @version $Rev$
 */
public abstract class AbstractMessageReceiver
  implements Runnable, StatisticsContainer, MessageReceiver, Configurable {

	public static final String MAX_QUEUE_SIZE_PROP_KEY = "max-queue-size";
	//  public static final Integer MAX_QUEUE_SIZE_PROP_VAL = Integer.MAX_VALUE;
  public static final Integer MAX_QUEUE_SIZE_PROP_VAL = 1000;

  /**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger("tigase.server.AbstractMessageReceiver");

  private int maxQueueSize = MAX_QUEUE_SIZE_PROP_VAL;

  private MessageReceiver parent = null;

  private LinkedBlockingQueue<QueueElement> queue =
		new LinkedBlockingQueue<QueueElement>(maxQueueSize);
	private Thread thread = null;
  private boolean stopped = false;
  private String name = null;
	private Set<String> routings = new TreeSet<String>();

  /**
   * Variable <code>statAddedMessagesOk</code> keeps counter of successfuly
   * added messages to queue.
   */
  private long statAddedMessagesOk = 0;
  /**
   * Variable <code>statAddedMessagesEr</code> keeps counter of unsuccessfuly
   * added messages due to queue overflow.
   */
  private long statAddedMessagesEr = 0;

//   /**
//    * Method <code>localAddresses</code> returns array of Strings.
//    * Each String should be a regular expression
//    * defining destination addresses for which this receiver can process
//    * messages. There can be more than one message receiver for each messages.
//    *
//    * @return a <code>String</code> value
//    */
//   public String[] getLocalAddresses() { return localAddresses; }

  /**
   * Describe <code>addMessage</code> method here.
   *
   * @param packet a <code>Packet</code> value
   */
  public boolean addPacket(Packet packet) {
    return prAddPacket(packet);
  }

  public boolean addPackets(Queue<Packet> packets) {
		Packet p = null;
		boolean result = true;
		while ((p = packets.peek()) != null) {
			result = prAddPacket(p);
			if (result) {
				packets.poll();

			} // end of if (result)
			else {
				return false;
			} // end of if (result) else
		} // end of while ()
    return true;
  }

	private boolean prAddPacket(Packet packet) {
		try {
			log.finest(">" + getName() + "<  " +
				"Added packet to inQueue: " + packet.getStringData());
			queue.put(new QueueElement(QueueElementType.IN_QUEUE, packet));
			++statAddedMessagesOk;
		} // end of try
		catch (InterruptedException e) {
			++statAddedMessagesEr;
			return false;
		} // end of try-catch
		return true;
  }

	protected boolean addOutPacket(Packet packet) {
		try {
			log.finest(">" + getName() + "<  " +
				"Added packet to outQueue: " + packet.getStringData());
			queue.put(new QueueElement(QueueElementType.OUT_QUEUE, packet));
			++statAddedMessagesOk;
		} // end of try
		catch (InterruptedException e) {
			++statAddedMessagesEr;
			return false;
		} // end of try-catch
		return true;
	}

  public void run() {
    while (! stopped) {
      try {
				QueueElement qel = queue.take();
				switch (qel.type) {
				case IN_QUEUE:
					processPacket(qel.packet);
					break;
				case OUT_QUEUE:
					if (parent != null) {
						log.finest(">" + getName() + "<  " +
							"Sending outQueue to parent: " + parent.getName());
						parent.addPacket(qel.packet);
					} // end of if (parent != null)
					else {
						log.warning(">" + getName() + "<  " + "No parent!");
					} // end of else
					break;
				default:
					log.severe("Unknown queue element type: " + qel.type);
					break;
				} // end of switch (qel.type)
      } catch (InterruptedException e) {
				stopped = true;
			} // end of try-catch
    } // end of while (! stopped)
  }

  public abstract void processPacket(Packet packet);

	//   public int queueSize() { return inQueue.size(); }

  /**
   * Returns defualt configuration settings for this object.
   */
  public List<StatRecord> getStatistics() {
    List<StatRecord> stats = new ArrayList<StatRecord>();
    stats.add(new StatRecord(StatisticType.QUEUE_SIZE, queue.size()));
    stats.add(new StatRecord(StatisticType.MSG_RECEIVED_OK,
				statAddedMessagesOk));
    stats.add(new StatRecord(StatisticType.QUEUE_OVERFLOW,
				statAddedMessagesEr));
    return stats;
  }

  /**
   * Sets all configuration properties for object.
   */
  public void setProperties(Map<String, Object> properties) {
    int queueSize = (Integer)properties.get(MAX_QUEUE_SIZE_PROP_KEY);
    setMaxQueueSize(queueSize);
  }

  public void setMaxQueueSize(int maxQueueSize) {
    if (this.maxQueueSize != maxQueueSize) {
      this.maxQueueSize = maxQueueSize;
      if (queue != null) {
				LinkedBlockingQueue<QueueElement> newQueue =
					new LinkedBlockingQueue<QueueElement>(maxQueueSize);
				newQueue.addAll(queue);
				queue = newQueue;
      } // end of if (queue != null)
    } // end of if (this.maxQueueSize != maxQueueSize)
  }

//   public void setLocalAddresses(String[] addresses) {
//     localAddresses = addresses;
//   }

  /**
   * Returns defualt configuration settings for this object.
   */
  public Map<String, Object> getDefaults() {
    Map<String, Object> defs = new TreeMap<String, Object>();
		defs.put(MAX_QUEUE_SIZE_PROP_KEY, MAX_QUEUE_SIZE_PROP_VAL);
    return defs;
  }

  public void release() {
    stop();
  }

  public void setParent(MessageReceiver parent) {
    this.parent = parent;
		addRouting(getDefHostName());
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public void start() {
		if (thread == null || ! thread.isAlive()) {
			stopped = false;
			thread = new Thread(this);
			thread.setName(name);
			thread.start();
		} // end of if (thread == null || ! thread.isAlive())
  }

  public void stop() {
    stopped = true;
		queue.notifyAll();
  }

	public String getDefHostName() {
		if (parent != null) {
			return parent.getDefHostName();
		} // end of if (parent != null)
		else {
			return null;
		} // end of if (parent != null) else
	}

	public Set<String> getRoutings() {
		return routings;
	}

	public void addRouting(String address) {
		routings.add(address);
	}

	public boolean removeRouting(String address) {
		return routings.remove(address);
	}

	private enum QueueElementType { IN_QUEUE, OUT_QUEUE }

	private class QueueElement {
		private QueueElementType type = null;
		private Packet packet = null;

		private QueueElement(QueueElementType type, Packet packet) {
			this.type = type;
			this.packet = packet;
		}

	}

} // AbstractMessageReceiver
