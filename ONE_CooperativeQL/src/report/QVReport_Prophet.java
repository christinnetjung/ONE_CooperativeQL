package report;

import java.util.List;

import core.DTNHost;
import core.SimScenario;
import routing.ActiveRouter;
import routing.CVDetectionEngine;
import routing.CVandTime;
import routing.MessageRouter;
import routing.QVDetectionEngine;

public class QVReport_Prophet extends Report {
	public QVReport_Prophet() {
		init();
	}

	public void done() {
		List<DTNHost> hosts = SimScenario.getInstance().getHosts();
		String write = " ";
		double hostsize = hosts.size();
		double[][] totalQV = new double[4][8];
		for (DTNHost h : hosts) {
			
			MessageRouter mr = h.getRouter();
			ActiveRouter ar = (ActiveRouter) mr;
			QVDetectionEngine qvde = (QVDetectionEngine) ar;

			double[][] QV = qvde.getQV();

			for (int i =0; i< QV.length;i++) {
				for(int j=0; j<QV[0].length; j++) {
					//write = write + "\t" + QV[i][j];
					totalQV[i][j]+=QV[i][j];
				}
				//write+="\n";
				
				
			}
		}
		
		for (int i =0; i< totalQV.length;i++) {
			for(int j=0; j<totalQV[0].length; j++) {
				write = write + "\t" + (totalQV[i][j]/hostsize) +", ";
			}
			write+="\n";
			
			
		}
		write(write);
		super.done();

	}
}
