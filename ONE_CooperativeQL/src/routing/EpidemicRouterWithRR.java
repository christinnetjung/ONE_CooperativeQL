/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Epidemic message router with drop-oldest buffer and only single transferring
 * connections at a time.
 */
public class EpidemicRouterWithRR extends ActiveRouter implements CVDetectionEngine {
// added - Retiring replicants's attributes -

	private int nrOfReps = 0; // inisialisasi jumlah replikasi
	private int nrOfDrops = 0; // inisialisasi jumlah drop
	private int limit = 1; // inisialisasi limit
	protected double CV = 0; // inisialisasi CV -> infinity (Double.POSITIVE_INFINITY;)
	private static final int AI = 1;
	private static final double MD = 0.2;
	public static final double ALPHA = 0.9;
	private Map<Connection, Integer> connLimit; // store conn along with limit
	protected List<CVandTime> cvList = new ArrayList<>();
	private Map<String, ACKTTL> receiptBuffer; // buffer that save receipt
	/**
	 * message that should be deleted
	 */
	private Set<String> messageReadytoDelete;

	/**
	 * Constructor. Creates a new message router based on the settings in the given
	 * Settings object.
	 *
	 * @param s The settings object
	 */
	/**
	 * Initializes Connection and Limit hash
	 */
	private void initConnLimit() {
		this.connLimit = new HashMap<Connection, Integer>();
	}

	public EpidemicRouterWithRR(Settings s) {
		super(s);
		initConnLimit();
		this.receiptBuffer = new HashMap<>();
		this.messageReadytoDelete = new HashSet<>();
		this.cvList = new ArrayList<CVandTime>();
		// TODO: read&use epidemic router specific settings (if any)
	}

	/**
	 * Copy constructor.
	 *
	 * @param r The router prototype where setting values are copied from
	 */
	protected EpidemicRouterWithRR(EpidemicRouterWithRR r) {
		super(r);
		initConnLimit();
		this.receiptBuffer = new HashMap<>();
		this.messageReadytoDelete = new HashSet<>();
		this.cvList = new ArrayList<CVandTime>();
		// TODO: copy epidemic settings here (if any)
	}

	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());

			connLimit.put(con, this.limit);
			Collection<Message> thisMessageList = getMessageCollection();
			EpidemicRouterWithRR othRouter = (EpidemicRouterWithRR) otherHost.getRouter();

			// Exchange receipt buffer
			Map<String, ACKTTL> peerReceiptBuffer = othRouter.getReceiptBuffer();

			for (Map.Entry<String, ACKTTL> entry : peerReceiptBuffer.entrySet()) {
				if (!receiptBuffer.containsKey(entry.getKey())) {
					receiptBuffer.put(entry.getKey(), entry.getValue());
				}
			}
			for (Message m : thisMessageList) {
				// Delete message that have a receipt
				if (receiptBuffer.containsKey(m.getId())) {
					messageReadytoDelete.add(m.getId());
				}
			}

			/*
			 * for (String m : messageReadytoDelete) { if (isSending(m)) { List<Connection>
			 * conList = getConnections(); for (Connection conn : conList) { if
			 * (conn.getMessage() != null && conn.getMessage().getId() == m) {
			 * conn.abortTransfer(); ; break; } } } deleteMessage(m, false); }
			 */
			for (String m : messageReadytoDelete) {

				deletemsg(m, false);
			}
			messageReadytoDelete.clear();

		} else {
			DTNHost otherHost = con.getOtherNode(getHost());
			double newCV = calculateCV(con, otherHost);
			CVandTime cvValue = new CVandTime(this.CV, SimClock.getTime());
			cvList.add(cvValue);
			if (newCV <= this.CV) {
				this.limit = this.limit + AI;
			} else {
				this.limit = (int) Math.ceil(this.limit * MD);
			}
			this.CV = newCV;
			connLimit.remove(con);
			messageReadytoDelete.clear();
		}
	}

	public void deletemsg(String msgID, boolean dropchecking) {
		if (isSending(msgID)) {
			List<Connection> conList = getConnections();
			for (Connection cons : conList) {
				if (cons.getMessage() != null && cons.getMessage().getId() == msgID) {
					cons.abortTransfer();
					break;
				}
			}
		}
		deleteMessage(msgID, dropchecking);
	}

	// ADDED NEW METHOD

	protected boolean makeRoomForMessage(int size) {
		if (size > this.getBufferSize()) {
			return false; // message too big for the buffer
		}

		int freeBuffer = this.getFreeBufferSize();
		/* delete messages from the buffer until there's enough space */
		while (freeBuffer < size) {
			Message m = getOldestMessage(true); // don't remove msgs being sent

			if (m == null) {
				return false; // couldn't remove any more messages
			}

			/* delete message from the buffer as "drop" */
			deleteMessage(m.getId(), true);
			nrOfDrops++;
			freeBuffer += m.getSize();
		}

		return true;
	}

	// Untuk mengembalikan nilai CV
	private double calculateCV(Connection con, DTNHost peer) {
		EpidemicRouterWithRR othRouter = (EpidemicRouterWithRR) peer.getRouter();
		int hopCount = messagestotalHops();
		int drops = this.nrOfDrops + othRouter.nrOfDrops;
		int reps = this.nrOfReps + othRouter.nrOfReps + hopCount;
		// reset
		nrOfDrops = 0;
		nrOfReps = 0;
		// added
		double rasio;
		if (reps != 0) {
			rasio = (double) drops / (double) reps;
		} else {
			// rasio = (double) drops / 0.0001;
			rasio = 1.0;
		}
		return (ALPHA * rasio) + ((1.0 - ALPHA) * CV);
	}

	// Menghitung total hop pada suatu pesan
	// Counting total hops of messages
	private int messagestotalHops() {
		Collection<Message> msg = getMessageCollection();
		int totalHops = 0;
		if (!msg.isEmpty()) {
			for (Message m : msg) {
				if (!(m.getHopCount() == 0)) {
					totalHops += (m.getHopCount() - 1);
				}
			}
		}
		return totalHops;
	}

	@Override
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {
			return; // transferring, don't try other connections yet
		}

		// Try first the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return; // started a transfer, don't try others (yet)
		}

		// then try any/all message to any/all connection
		this.tryAllMessagesToAllConnections();
	}

	/*
	 * @Override protected Message tryAllMessages(Connection con, List<Message>
	 * messages) { for (Message m : messages) { if (connLimit.containsKey(con)) {
	 * int retVal = startTransfer(m, con); if (retVal == RCV_OK) { int remainLimit =
	 * connLimit.get(con) - 1; if (remainLimit != 0) { connLimit.replace(con,
	 * remainLimit); } else { connLimit.remove(con); } return m; // accepted a
	 * message, don't try others } else if (retVal > 0) { return null; // should try
	 * later -> don't bother trying others } } } return null; }
	 */
	@Override
	protected int startTransfer(Message m, Connection con) {
		int retVal;

		if (!con.isReadyForTransfer()) {
			return TRY_LATER_BUSY;
		}
		if (connLimit.containsKey(con)) {
			retVal = con.startTransfer(getHost(), m);
			if (retVal == RCV_OK) { // started transfer
				addToSendingConnections(con);
				int remainingLimit = connLimit.get(con) - 1;
				if (remainingLimit != 0) {
					connLimit.replace(con, remainingLimit);
				} else {
					connLimit.remove(con);
				}
			} else if (deleteDelivered && retVal == DENIED_OLD && m.getTo() == con.getOtherNode(this.getHost())) {
				/* final recipient has already received the msg -> delete it */
				this.deleteMessage(m.getId(), false);

			}
			return retVal;
		}
		return DENIED_UNSPECIFIED;
	}

	/*
	 * @Override public int receiveMessage(Message m, DTNHost from) { int recvCheck
	 * = checkReceiving(m); if (recvCheck != RCV_OK) { return recvCheck; }
	 * this.nrOfReps++; // add -> this.nrOfReps++ // - ACK - if (isFinalDest(m,
	 * this.getHost()) && !receiptBuffer.containsKey(m.getId())) { ACKTTL ack = new
	 * ACKTTL(SimClock.getTime(), m.getTtl()); receiptBuffer.put(m.getId(), ack); }
	 * // seems OK, start receiving the message return super.receiveMessage(m,
	 * from); }
	 */

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		this.nrOfReps++; // add -> this.nrOfReps++
		// - ACK -
		if (isFinalDest(msg, this.getHost()) && !receiptBuffer.containsKey(msg.getId())) {
			ACKTTL ack = new ACKTTL(SimClock.getTime(), msg.getTtl());
			receiptBuffer.put(msg.getId(), ack);
		}
		return msg;
	}

	// check if this host is final destination
	public boolean isFinalDest(Message m, DTNHost aHost) {

		return m.getTo().equals(aHost);
	}

	@Override
	public EpidemicRouterWithRR replicate() {
		return new EpidemicRouterWithRR(this);
	}

	@Override
	public List<CVandTime> getCVandTime() {
		return this.cvList;
	}

	public Map<String, ACKTTL> getReceiptBuffer() {
		return receiptBuffer;
	}

}
