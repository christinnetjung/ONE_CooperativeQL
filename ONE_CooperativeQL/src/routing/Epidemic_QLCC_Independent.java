/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.*;

import core.*;
import routing.*;
import routing.QL.*;

/**
 * Implementation of PRoPHET router as described in <I>Probabilistic routing in
 * intermittently connected networks</I> by Anders Lindgren et al.
 */
public abstract class Epidemic_QLCC_Independent extends ActiveRouter implements CVDetectionEngine, QVDetectionEngine, TotalActionChosen {

	/** NewQLCC router's setting namespace ({@value}) */
	public static final String NewQLCC_NS = "eIQLCC";
	/** AI (additive increase) - setting id (@value) */
	public static final String AI_S = "ai";
	/** MD (multiplicative decrease) - setting id (@value) */
	public static final String MD_S = "md";
	/** Alpha for CV's learning rate - setting id (@value) */
	public static final String ALPHA_CV_S ="alphaCV";
	/** constant K for decrease or increase message generation rate - setting id (@value) */
	public static final String K_S = "k";
	/** minimum time for update new state (window) - setting id (@value) */
	public static final String STATE_UPDATE_INTERVAL_S = "stateInterval";
	
	/** default value for ai */
	public static final int DEFAULT_AI = 1;
	/** default value for md */
	public static final double DEFAULT_MD = 0.2;
	/** default value for alpha */
	public static final double DEFAULT_ALPHA_CV = 0.9;
	/** default value for constant K */
	public static final double DEFAULT_K = 2.0;
	/** default value for state interval update */
	public static final double DEFAULT_STATE_UPDATE_INTERVAL = 300;
	
	/**value of md setting*/
	private double md;
	/**value of ai setting*/
	private int ai;
	/**value of cv alpha setting*/
	private double alpha;
	/**value of stateUpdateInterval setting*/
	private double stateUpdateInterval;
	
	/** dumb variable to count number of reps */
	private int nrofreps = 0;
	/** dumb variable to count number of drops */
	private int nrofdrops = 0;
	/** dumb variable to count number of reps other hosts */
	private int otherNrofReps=0;
	/** dumb variable to count number of drops other hosts */
	private int otherNrofDrops=0;
	/** to count limit for a connection */
	private int msglimit = 1;
	
	/** ratio of drops and reps 8*/
	private double ratio = 0;
	/** congestion value - ratio of drops and reps which counted with EWMA equation*/
	private double CV = 0;
	
	/** a buffer to save information about a connection and its limits to send*/
	private Map<Connection, Integer> conlimitmap;
	
	/** dumb variable to count interval for count new CV*/
	private double cvCountInterval = 0;

	/** for cv and time interface */
	private List<CVandTime> cvandtime;

	/** buffer that save receipt */
	protected Map<String, ACKTTL> receiptBuffer;

	/** message that should be deleted */
	protected Set<String> messageReadytoDelete;

	/** QL object init */
	private QLearning QL;

	/** action restriction checking table for each state */
	protected boolean[][] actionRestriction = { 
			{ true, true, true, true, false, true, false },
			{ false, false, true, false, true, false, true },
			{ false, false, true, false, true, false, true },
			{ true, true, true, true, false, true, false }};
			

	/** exploration policy */
	protected IExplorationPolicy explorationPolicy = new BoltzmannExploration(1);

	/** init state for congested*/
	private static final int C = 0;
	/** init state for Non-congested*/
	private static final int NC = 1;
	/** init state for Decrease congested*/
	private static final int DC = 2;
	/** init state for Prospective congested*/
	private static final int PC = 3;

	/** a variable to save information the current oldstate.
	 * at first, it has value -1, so the router able to know if it is the first time
	 * to observe the environment and set a state */
	protected int oldstate = -1;

	/** for save an information about the last selection action*/
	protected int actionChosen;

	/** message generation interval in seconds */
	protected double msggenerationinterval = 600;
	/** constant k for increase or decrease message generation rate */
	private double k = 2;
	
	/** for record the last time of message creation */
	private double endtimeofmsgcreation = 0;

	/** message property to record its number of copies */
	public static final String repsproperty = "nrofcopies";
	
	private int action7 = 0;
	private int totalaction = 0;

	/**
	 * Constructor. Creates a new message router based on the settings in the given
	 * Settings object.
	 * 
	 * @param s The settings object
	 */
	public Epidemic_QLCC_Independent(Settings s) {
		super(s);
		Settings NewQLCCSettings = new Settings(NewQLCC_NS);
		
		if (NewQLCCSettings.contains(AI_S)) {
			ai = NewQLCCSettings.getInt(AI_S);
		} else {
			ai = DEFAULT_AI;
		}
		
		if (NewQLCCSettings.contains(MD_S)) {
			md = NewQLCCSettings.getDouble(MD_S);
		} else {
			md = DEFAULT_MD;
		}
		
		if (NewQLCCSettings.contains(ALPHA_CV_S)) {
			alpha = NewQLCCSettings.getDouble(ALPHA_CV_S);
		} else {
			alpha = DEFAULT_ALPHA_CV;
		}
		
		if (NewQLCCSettings.contains(STATE_UPDATE_INTERVAL_S)) {
			stateUpdateInterval = NewQLCCSettings.getDouble(STATE_UPDATE_INTERVAL_S);
		} else {
			stateUpdateInterval = DEFAULT_STATE_UPDATE_INTERVAL;
		}
		
		if (NewQLCCSettings.contains(K_S)) {
			k = NewQLCCSettings.getDouble(K_S);
		} else {
			k = DEFAULT_K;
		}
		
		explorationPolicy();
		initQL();
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
	protected Epidemic_QLCC_Independent(Epidemic_QLCC_Independent r) {
		super(r);
		this.ai = r.ai;
		this.md = r.md;
		this.alpha = r.alpha;
		this.k = r.k;
		this.stateUpdateInterval = r.stateUpdateInterval;
		explorationPolicy();
		initQL();
		limitconmap();
		cvtimelist();
		receiptbuffer();
		msgreadytodelete();
	}

	/**
	 * Initializes predictability hash
	 */
	protected void explorationPolicy() {
		this.explorationPolicy = new BoltzmannExploration(1);
	}

	protected void initQL() {

		this.QL = new QLearning(this.actionRestriction.length, this.actionRestriction[0].length, this.explorationPolicy,
				false, this.actionRestriction);

	}

	protected void limitconmap() {
		this.conlimitmap = new HashMap<Connection, Integer>();
	}

	protected void cvtimelist() {
		this.cvandtime = new ArrayList<CVandTime>();
	}

	protected void receiptbuffer() {
		this.receiptBuffer = new HashMap<>();
	}

	protected void msgreadytodelete() {
		this.messageReadytoDelete = new HashSet<>();
	}

	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {

			connectionUp(con);
			DTNHost otherHost = con.getOtherNode(getHost());

			conlimitmap.put(con, this.msglimit);
			System.out.println(this.msglimit);

			Collection<Message> thisMsgCollection = getMessageCollection();

			Epidemic_QLCC_Independent peerRouter = (Epidemic_QLCC_Independent) otherHost.getRouter();
			exchangemsginformation();
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
			// delete transferred msg
			for (String m : messageReadytoDelete) {

				deletemsg(m, false);
			}

			messageReadytoDelete.clear();
		} else {
			connectionDown(con);
			DTNHost otherHost = con.getOtherNode(getHost());
			Epidemic_QLCC_Independent peerRouter = (Epidemic_QLCC_Independent) otherHost.getRouter();
			if (cvCountInterval == 0 || (SimClock.getTime() - cvCountInterval) >= stateUpdateInterval) {
				
				double newCV = countcv(con, otherHost);
				CVandTime nilaicv = new CVandTime(this.CV, SimClock.getTime());
				cvandtime.add(nilaicv);
				if (this.oldstate == -1) {
					oldstate = staterequirement(this.CV, newCV);
					actionChosen = this.QL.GetAction(oldstate);
					this.actionSelectionController(actionChosen);
				} else {
					int newstate = staterequirement(this.CV, newCV);
					this.updateState(newstate);
					// System.out.println(newstate);
				}
				this.CV = newCV;
				cvCountInterval=SimClock.getTime();
				// System.out.println(newCV);
			} else {
				otherNrofDrops += peerRouter.getNrofDrops();
				otherNrofReps += peerRouter.getNrofReps();
			}
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

		// tryAllMessageToAllConections();
	}

	/* exchange mesage's information of the reps number **/
	protected void exchangemsginformation() {
		Collection<Message> msgCollection = getMessageCollection();
		for (Connection con : getConnections()) {
			DTNHost peer = con.getOtherNode(getHost());
			Epidemic_QLCC_Independent other = (Epidemic_QLCC_Independent) peer.getRouter();
			if (other.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			for (Message m : msgCollection) {
				if (other.hasMessage(m.getId())) {
					Message temp = other.getMessage(m.getId());
					/* take the max reps */
					if ((Integer) m.getProperty(repsproperty) < (Integer) temp.getProperty(repsproperty)) {
						m.updateProperty(repsproperty, temp.getProperty(repsproperty));
					}
				}

			}
		}
	}

	protected void updateState(int newstate) {

		double reward = checkReward(oldstate, newstate);

		this.QL.UpdateState(oldstate, actionChosen, reward, newstate);

		int newestAction = this.QL.GetAction(newstate);
		if (oldstate == NC) {
			totalaction++;
			if (newestAction == 7) {
				action7++;
			}
		}

		this.actionSelectionController(newestAction);
		this.oldstate = newstate;
		this.actionChosen = newestAction;
		//System.out.println(newestAction);
		BoltzmannExploration exp = (BoltzmannExploration) this.QL.getExplorationPolicy();
		double temp = exp.getTemperature();
		if (temp != 0) {
			/** do exp 50 times */
			exp.setTemperature(temp - 0.02);
		}
		if (temp <= 0) {
			exp.setTemperature(0);
		}
		this.QL.setExplorationPolicy(exp);
		// System.out.println(temp);
	}

	/** action selection controller */
	public void actionSelectionController(int action) {
		switch (action) {
		case 0:
			this.dropbasedonhighestrate();
			break;
		case 1:
			this.dropbasedonhighestnrofreps();
			break;
		case 2:
			this.dropbasedonoldestTTL();
			break;
		case 3:
			this.increasemessagegenerationperiod();
			break;
		case 4:
			this.decreasemessagegenerationperiod();
			break;
		case 5:
			this.decreasingnrofreps();
			break;
		case 6:
			this.increasingnrofreps();
			break;

		}
	}

	protected int staterequirement(double oldcv, double newcv) {
		if (ratio >= 0.1) {
			return C;
		} else if (ratio <= 0.01) {
			return NC;
		} else if (newcv <= oldcv) {
			return DC;
		} else {
			return PC;
		}

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

	/** buffer checking */
	@Override
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
	public boolean createNewMessage(Message m) {

		if (this.endtimeofmsgcreation == 0
				|| SimClock.getTime() - this.endtimeofmsgcreation >= this.msggenerationinterval) {
			this.endtimeofmsgcreation = SimClock.getTime();
			m.addProperty(repsproperty, 1);
			return super.createNewMessage(m);
		}

		return false;

	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message aCopy = super.messageTransferred(id, from);
		Integer msgprop = ((Integer) aCopy.getProperty(repsproperty)) + 1;

		aCopy.updateProperty(repsproperty, msgprop);

		// number of replication increased by 1
		nrofreps++;
		// ack
		if (isFinalDest(aCopy, this.getHost()) && !receiptBuffer.containsKey(aCopy.getId())) {
			ACKTTL ack = new ACKTTL(SimClock.getTime(), aCopy.getTtl());
			receiptBuffer.put(aCopy.getId(), ack);
		}

		return aCopy;
	}

	/** count message hops */
	protected int msgtotalhops() {
		Collection<Message> msg = getMessageCollection();
		int totalhops = 0;
		if (!msg.isEmpty()) {
			for (Message m : msg) {
				if (!(m.getHopCount() == 0)) {
					totalhops += (m.getHopCount() - 1);
				}
			}
		}
		return totalhops;
	}

	/** count CV */
	protected double countcv(Connection con, DTNHost other) {
		Epidemic_QLCC_Independent peerRouter = (Epidemic_QLCC_Independent) other.getRouter();
		int totalhops = msgtotalhops();
		int totaldrop = this.nrofdrops + peerRouter.nrofdrops + this.otherNrofDrops;
		int totalreps = this.nrofreps + peerRouter.nrofreps + totalhops + this.otherNrofReps;

		// reset
		nrofdrops = 0;
		nrofreps = 0;
		otherNrofDrops = 0;
		otherNrofReps = 0;
		
		double ratio;
		if (totalreps != 0) {
			ratio = (double) totaldrop / (double) totalreps;
		} else {
			ratio = 1.0;
		}
		this.ratio = ratio;
		return (alpha * ratio) + ((1.0 - alpha) * CV);

	}

	/** check if this host is the final dest */
	protected boolean isFinalDest(Message m, DTNHost thisHost) {
		return m.getTo().equals(thisHost);
	}

	protected double checkReward(int olds, int news) {
		if (olds == NC && news == PC) {
			return -1.0;
		} else if (olds == DC && news == PC) {
			return -1.0;
		} else if (olds == DC && news == DC) {
			return -0.5;
		} else if (olds == PC && news == PC) {
			return -1.0;
		} else if (olds == PC && news == DC) {
			return 0.5;
		} else if (olds == C && news == DC) {
			return 1.0;
		} else if (news == C) {
			return -2.0;
		} else {
			return 2.0;
		}

	}

	/* Message's rate comparator class */
	public class RateComparator implements Comparator<Tuple<Message, Double>> {

		public int compare(Tuple<Message, Double> ratetuple1, Tuple<Message, Double> ratetuple2) {

			double r1 = ratetuple1.getValue();
			double r2 = ratetuple2.getValue();

			/* descending sort */
			if (r2 - r1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(ratetuple1.getKey(), ratetuple2.getKey());
			} else if (r2 - r1 < 0) {
				return -1;
			} else {
				return 1;
			}

		}

	}

	/* Message's TTL comparator class */
	public class TTLComparator implements Comparator<Message> {

		public int compare(Message msgttl1, Message msgttl2) {

			double ttl1 = msgttl1.getTtl();
			double ttl2 = msgttl2.getTtl();

			/* ascending sort */
			if (ttl2 - ttl1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(msgttl1, msgttl2);
			} else if (ttl2 - ttl1 < 0) {
				return 1;
			} else {
				return -1;
			}

		}

	}

	/* Total number of message's replication comparator class */
	public class NrOfRepsComparator implements Comparator<Message> {

		public int compare(Message msg1, Message msg2) {

			double reps1 = (Integer) msg1.getProperty(repsproperty);
			double reps2 = (Integer) msg2.getProperty(repsproperty);

			/* descending sort */
			if (reps2 - reps1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(msg1, msg2);
			} else if (reps2 - reps1 < 0) {
				return -1;
			} else {
				return 1;
			}

		}

	}

	/* Message's DP comparator class */
	public class DPComparator implements Comparator<Tuple<Message, Double>> {

		public int compare(Tuple<Message, Double> dptuple1, Tuple<Message, Double> dptuple2) {

			double dp1 = dptuple1.getValue();
			double dp2 = dptuple2.getValue();

			/* ascending sort */
			if (dp2 - dp1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(dptuple1.getKey(), dptuple2.getKey());
			} else if (dp2 - dp1 < 0) {
				return 1;
			} else {
				return -1;
			}

		}

	}

	/* QL ACTION METHODS */
	protected void dropbasedonhighestrate() {
		List<Tuple<Message, Double>> messages = new ArrayList<Tuple<Message, Double>>();
		Collection<Message> msgCollection = getMessageCollection();

		for (Message m : msgCollection) {
			double msgrate = ((double) m.getHops().size() - 1.0) / ((double) m.getInitTTL() - (double) m.getTtl());
			messages.add(new Tuple<Message, Double>(m, msgrate));
		}

		Collections.sort(messages, new RateComparator());

		for (Tuple<Message, Double> highestratemsg : messages) {
			double deleteth = this.getFreeBufferSize() + 0.3 * (this.getBufferSize() - this.getFreeBufferSize());
			if (this.getFreeBufferSize() < deleteth) {
				// nrofdrops++;
				deleteMessage(highestratemsg.getKey().getId(), false);
			}
			break;
		}
	}

	protected void dropbasedonhighestnrofreps() {
		List<Message> messages = new ArrayList<Message>();
		Collection<Message> msgCollection = getMessageCollection();

		for (Message m : msgCollection) {
			messages.add(m);
		}

		Collections.sort(messages, new NrOfRepsComparator());
		for (Message highestreps : messages) {
			double deleteth = this.getFreeBufferSize() + 0.3 * (this.getBufferSize() - this.getFreeBufferSize());
			if (this.getFreeBufferSize() < deleteth) {
				// nrofdrops++;
				deletemsg(highestreps.getId(), false);
			}
			break;
		}

	}

	protected void dropbasedonoldestTTL() {
		List<Message> messages = new ArrayList<Message>();
		Collection<Message> msgCollection = getMessageCollection();

		for (Message m : msgCollection) {
			messages.add(m);
		}

		Collections.sort(messages, new TTLComparator());
		for (Message oldestTTLmsg : messages) {
			double deleteth = this.getFreeBufferSize() + 0.3 * (this.getBufferSize() - this.getFreeBufferSize());
			if (this.getFreeBufferSize() < deleteth) {
				// nrofdrops++;
				deletemsg(oldestTTLmsg.getId(), false);
			}
			break;
		}

	}

	protected void increasemessagegenerationperiod() {
		this.msggenerationinterval *= k;

	}

	protected void decreasemessagegenerationperiod() {
		this.msggenerationinterval /= k;

	}

	protected void decreasingnrofreps() {
		this.msglimit = (int) Math.ceil(this.msglimit * md);

	}

	protected void increasingnrofreps() {
		this.msglimit = this.msglimit + ai;

	}

	/** things to do when connection up*/
	public void connectionUp(Connection con) {

	}
	/** things to do when connection down*/
	public void connectionDown(Connection con) {

	}

	public Map<String, ACKTTL> getReceiptBuffer() {
		return receiptBuffer;
	}
	
	public int getNrofReps() {
		return this.nrofreps;
	}
	
	public int getNrofDrops() {
		return this.nrofdrops;
	}
	
	@Override
	public List<CVandTime> getCVandTime() {
		return this.cvandtime;
	}

	@Override
	public double[][] getQV() {
		return this.QL.getqvalues();
	}

	@Override
	public int getAction7total() {
		return action7;
	}

	@Override
	public int getTotalAction() {
		return totalaction;
	}

}
