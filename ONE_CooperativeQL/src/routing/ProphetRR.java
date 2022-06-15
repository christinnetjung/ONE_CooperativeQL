/* Copyright 2010 Aalto University, ComNet
* Released under GPLv3. See LICENSE.txt for details. 
*/
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import core.Tuple;

/**
 * Implementation of PRoPHET router as described in <I>Probabilistic routing in
 * intermittently connected networks</I> by Anders Lindgren et al.
 */
public class ProphetRR extends ActiveRouter implements CVDetectionEngine {
	/** delivery predictability initialization constant */
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;

	/** Prophet router's setting namespace ({@value}) */
	public static final String PROPHET_NS = "ProphetRR";
	/**
	 * Number of seconds in time unit -setting id ({@value}). How many seconds one
	 * time unit is when calculating aging of delivery predictions. Should be
	 * tweaked for the scenario.
	 */
	public static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";

	/**
	 * Transitivity scaling constant (beta) -setting id ({@value}). Default value
	 * for setting is {@link #DEFAULT_BETA}.
	 */
	public static final String BETA_S = "beta";

	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;

	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;

	// RR
	private int nrofreps = 0;
	private int nrofdrops = 0;
	private int msglimit = 1;
	private double CV = 0;
	// private static final double md = 0.2;
	// private static final int ai = 1;
	public static final double ALPHA = 0.9;
	private Map<Connection, Integer> conlimitmap;

	// for cv and time interface
	public List<CVandTime> cvandtime;

	/** buffer that save receipt */
	public Map<String, ACKTTL> receiptBuffer;

	/** message that should be deleted */
	private Set<String> messageReadytoDelete;

	/**
	 * Constructor. Creates a new message router based on the settings in the given
	 * Settings object.
	 * 
	 * @param s The settings object
	 */
	public ProphetRR(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		} else {
			beta = DEFAULT_BETA;
		}

		initPreds();
		limitconmap();
		cvtimelist();
		receiptbuffer();
		msgreadytodelete();
	}

	/**
	 * Copyconstructor.
	 * 
	 * @param r The router prototype where setting values are copied from
	 */
	protected ProphetRR(ProphetRR r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		initPreds();
		limitconmap();
		cvtimelist();
		receiptbuffer();
		msgreadytodelete();
	}

	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}

	private void limitconmap() {
		this.conlimitmap = new HashMap<Connection, Integer>();
	}

	private void cvtimelist() {
		this.cvandtime = new ArrayList<CVandTime>();
	}

	private void receiptbuffer() {
		this.receiptBuffer = new HashMap<>();
	}

	private void msgreadytodelete() {
		this.messageReadytoDelete = new HashSet<>();
	}

	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());

			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);

			conlimitmap.put(con, this.msglimit);
			System.out.println(this.msglimit);

			Collection<Message> thisMsgCollection = getMessageCollection();

			ProphetRR peerRouter = (ProphetRR) otherHost.getRouter();

			Map<String, ACKTTL> peerRB = peerRouter.getReceiptBuffer();
			for (Map.Entry<String, ACKTTL> entry : peerRB.entrySet()) {
				if (!receiptBuffer.containsKey(entry.getKey())) {
					receiptBuffer.put(entry.getKey(), entry.getValue());

				}

			}
			for (Message m : thisMsgCollection) {
				/** Delete message that have a receipt */
				if (receiptBuffer.containsKey(m.getId())) {

					messageReadytoDelete.add(m.getId());
				}
			}

			/*
			 * for (String m : messageReadytoDelete) { if (isSending(m)) { List<Connection>
			 * conList = getConnections(); for (Connection cons : conList) { if
			 * (cons.getMessage() != null && cons.getMessage().getId() == m) {
			 * cons.abortTransfer(); break; } } } deleteMessage(m, false); }
			 */

			// delete transferred msg

			for (String m : messageReadytoDelete) {

				deletemsg(m, false);
			}

			messageReadytoDelete.clear();
		} else {
			DTNHost otherHost = con.getOtherNode(getHost());
			double newCV = countcv(con, otherHost);
			CVandTime nilaicv = new CVandTime(this.CV, SimClock.getTime());
			cvandtime.add(nilaicv);
			if (newCV <= this.CV) {
				this.msglimit = this.msglimit + 1;
			} else {
				this.msglimit = (int) Math.ceil(this.msglimit * 0.2);
			}
			this.CV = newCV;

			conlimitmap.remove(con);
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

	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * 
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		preds.put(host, newValue);
	}

	/**
	 * Returns the current prediction (P) value for a host or 0 if entry for the
	 * host doesn't exist.
	 * 
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		} else {
			return 0;
		}
	}

	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * 
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) {
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof ProphetRR : "PRoPHET only works " + " with other routers of same type";

		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = ((ProphetRR) otherRouter).getDeliveryPreds();

		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}

			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta;
			preds.put(e.getKey(), pNew);
		}
	}

	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of time
	 * units that have elapsed since the last time the metric was aged.
	 * 
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / secondsInTimeUnit;

		if (timeDiff == 0) {
			return;
		}

		double mult = Math.pow(GAMMA, timeDiff);
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			e.setValue(e.getValue() * mult);
		}

		this.lastAgeUpdate = SimClock.getTime();
	}

	/**
	 * Returns a map of this router's delivery predictions
	 * 
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}

	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring
		}

		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}

		tryOtherMessages();
	}

	/**
	 * Tries to send all other messages to all connected hosts ordered by their
	 * delivery probability
	 * 
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
		List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();

		Collection<Message> msgCollection = getMessageCollection();

		/*
		 * for all connected hosts collect all messages that have a higher probability
		 * of delivery by the other host
		 */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			ProphetRR othRouter = (ProphetRR) other.getRouter();

			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}

			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}
				if (othRouter.getPredFor(m.getTo()) > getPredFor(m.getTo())) {
					// the other node has higher probability of delivery
					messages.add(new Tuple<Message, Connection>(m, con));
				}
			}
		}

		if (messages.size() == 0) {
			return null;
		}

		// sort the message-connection tuples
		Collections.sort(messages, new TupleComparator());
		return tryMessagesForConnected(messages);

	}

	@Override
	protected int startTransfer(Message m, Connection con) {
		int retVal;

		if (!con.isReadyForTransfer()) {
			return TRY_LATER_BUSY;
		}
		if (conlimitmap.containsKey(con)) {
			retVal = con.startTransfer(getHost(), m);
			if (retVal == RCV_OK) { // started transfer
				addToSendingConnections(con);
				int remaininglimit = conlimitmap.get(con) - 1;
				if (remaininglimit != 0) {
					conlimitmap.replace(con, remaininglimit);
				} else {
					conlimitmap.remove(con);
				}
			} else if (deleteDelivered && retVal == DENIED_OLD && m.getTo() == con.getOtherNode(this.getHost())) {
				/* final recipient has already received the msg -> delete it */
				this.deleteMessage(m.getId(), false);
			}
			return retVal;
		}

		return DENIED_UNSPECIFIED;

	}

	/**
	 * Comparator for Message-Connection-Tuples that orders the tuples by their
	 * delivery probability by the host on the other side of the connection
	 * (GRTRMax)
	 */
	private class TupleComparator implements Comparator<Tuple<Message, Connection>> {

		public int compare(Tuple<Message, Connection> tuple1, Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = ((ProphetRR) tuple1.getValue().getOtherNode(getHost()).getRouter())
					.getPredFor(tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((ProphetRR) tuple2.getValue().getOtherNode(getHost()).getRouter())
					.getPredFor(tuple2.getKey().getTo());

			// bigger probability should come first
			if (p2 - p1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			} else if (p2 - p1 < 0) {
				return -1;
			} else {
				return 1;
			}
		}
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
			nrofdrops++;
			freeBuffer += m.getSize();
		}

		return true;
	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message aCopy = super.messageTransferred(id, from);

		// no of replication increased by 1
		nrofreps++;
		// ack
		if (isFinalDest(aCopy, this.getHost()) && !receiptBuffer.containsKey(aCopy.getId())) {
			ACKTTL ack = new ACKTTL(SimClock.getTime(), aCopy.getTtl());
			receiptBuffer.put(aCopy.getId(), ack);
		}

		return aCopy;
	}

	/**
	 * @Override public int receiveMessage(Message m, DTNHost from) { int recvCheck
	 *           = checkReceiving(m); if (recvCheck != RCV_OK) { return recvCheck; }
	 *           // no of replication increased by 1 nrofreps++; //ack if
	 *           (isFinalDest(m, this.getHost()) &&
	 *           !receiptBuffer.containsKey(m.getId())) { ACKTTL ack = new ACKTTL
	 *           (SimClock.getTime(),m.getTtl()); receiptBuffer.put(m.getId(), ack);
	 *           }
	 * 
	 *           // seems OK, start receiving the message return
	 *           super.receiveMessage(m, from); }
	 */

	// count message hops
	private int msgtotalhops() {
		Collection<Message> msg = getMessageCollection();
		int totalhops = 0;
		if (!msg.isEmpty()) {
			for (Message m : msg) {
				if (!(m.getHopCount() == 0)) {
					totalhops = totalhops + (m.getHopCount() - 1);
				}
			}
		}
		return totalhops;
	}

	// count CV
	private double countcv(Connection con, DTNHost other) {
		ProphetRR peerRouter = (ProphetRR) other.getRouter();
		int totalhops = msgtotalhops();
		int totaldrop = this.nrofdrops + peerRouter.nrofdrops;
		int totalreps = this.nrofreps + peerRouter.nrofreps + totalhops;
		// reset
		nrofdrops = 0;
		nrofreps = 0;
		double ratio;
		if (totalreps != 0) {
			ratio = (double) totaldrop / (double) totalreps;
		} else {
			// ratio = (double) totaldrop / 0.1;
			ratio = 1.0;
		}
		return (ALPHA * ratio) + ((1.0 - ALPHA) * CV);

	}

	// check if this host is the final dest
	private boolean isFinalDest(Message m, DTNHost thisHost) {
		return m.getTo().equals(thisHost);
	}

	@Override
	public RoutingInfo getRoutingInfo() {
		ageDeliveryPreds();
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(preds.size() + " delivery prediction(s)");

		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			DTNHost host = e.getKey();
			Double value = e.getValue();

			ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", host, value)));
		}

		top.addMoreInfo(ri);
		return top;
	}

	@Override
	public MessageRouter replicate() {
		ProphetRR r = new ProphetRR(this);
		return r;
	}

	@Override
	public List<CVandTime> getCVandTime() {
		// TODO Auto-generated method stub
		return this.cvandtime;
	}

	public Map<String, ACKTTL> getReceiptBuffer() {
		return receiptBuffer;
	}

	/*
	 * BESOK PR: 1. KNP DR MASIH LEBIH KECIL DR PROPET BIASA
	 */
}
