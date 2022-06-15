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
public class EpidemicForQL extends QLCC_Cooperative {

	public EpidemicForQL(Settings s) {
		super(s);

		// TODO: read&use epidemic router specific settings (if any)
	}

	/**
	 * Copy constructor.
	 *
	 * @param r The router prototype where setting values are copied from
	 */
	protected EpidemicForQL(EpidemicForQL r) {
		super(r);

		// TODO: copy epidemic settings here (if any)
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

	@Override
	public EpidemicForQL replicate() {
		return new EpidemicForQL(this);
	}

}
