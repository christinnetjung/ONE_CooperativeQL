package report;

import java.util.List;

import core.DTNHost;
import core.SimScenario;
import routing.ActiveRouter;
import routing.MessageRouter;
import routing.QVDetectionEngine;
import routing.TotalActionChosen;

public class TotalActionDetect extends Report {
	int totalaction7=0;
	int totalallaction=0;
	public TotalActionDetect() {
		init();
	}

	public void done() {
		List<DTNHost> hosts = SimScenario.getInstance().getHosts();
		String write = " ";
		double hostsize = hosts.size();
		
		for (DTNHost h : hosts) {
			
			MessageRouter mr = h.getRouter();
			ActiveRouter ar = (ActiveRouter) mr;
			TotalActionChosen qvde = (TotalActionChosen) ar;

			this.totalaction7+=qvde.getAction7total();
			this.totalallaction+=qvde.getTotalAction();
		}
		
		write=totalaction7+" , "+totalallaction;
		write(write);
		super.done();

	}
}
