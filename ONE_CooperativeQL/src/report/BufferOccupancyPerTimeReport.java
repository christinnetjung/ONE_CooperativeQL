/* 
 * 
 * 
 */
package report;

/**
 * Records the average buffer occupancy and its variance with format:
 * <p>
 * <Simulation time> <average buffer occupancy % [0..100]> <variance>
 * </p>
 *
 *
 */
import java.util.*;
//import java.util.List;
//import java.util.Map;

import core.DTNHost;
import core.Settings;
import core.SimClock;
import core.UpdateListener;

public class BufferOccupancyPerTimeReport extends Report implements UpdateListener {

    /**
     * Record occupancy every nth second -setting id ({@value}). Defines the
     * interval how often (seconds) a new snapshot of buffer occupancy is taken
     * previous:5
     */
    public static final String BUFFER_REPORT_INTERVAL = "occupancyInterval";
    /**
     * Default value for the snapshot interval
     */
    public static final int DEFAULT_BUFFER_REPORT_INTERVAL = 60;

    private double lastRecord = Double.MIN_VALUE;
    private int interval;

    private Map<DTNHost, Double> bufferCounts = new HashMap<DTNHost, Double>();
    private Map<DTNHost, ArrayList<Double>> bufferCountsTimes = new HashMap<DTNHost, ArrayList<Double>>();
    private int updateCounter = 0;  //new added

    public BufferOccupancyPerTimeReport() {
        super();

        Settings settings = getSettings();
        if (settings.contains(BUFFER_REPORT_INTERVAL)) {
            interval = settings.getInt(BUFFER_REPORT_INTERVAL);
        } else {
            interval = -1;
            /* not found; use default */
        }

        if (interval < 0) {
            /* not found or invalid value -> use default */
            interval = DEFAULT_BUFFER_REPORT_INTERVAL;
        }
    }

    public void updated(List<DTNHost> hosts) {
        if (isWarmup()) {
            return;
        }
        if (SimClock.getTime() - lastRecord >= interval) {
            lastRecord = SimClock.getTime();
            printLine(hosts);
            for(DTNHost h : hosts){
                if(bufferCountsTimes.containsKey(h)){
                    ArrayList<Double> bufferList = bufferCountsTimes.get(h);
                    bufferList.add(bufferCounts.get(h));
                    bufferCountsTimes.put(h, bufferList);
                } else {
                    ArrayList<Double> bufferList = new ArrayList<Double>();
                    bufferList.add(bufferCounts.get(h));
                    bufferCountsTimes.put(h, bufferList);
                }
            }
            bufferCounts.clear();
            updateCounter++; // new added
        }
        /**
         * for (DTNHost ho : hosts ) { double temp = ho.getBufferOccupancy();
         * temp = (temp<=100.0)?(temp):(100.0); if
         * (bufferCounts.containsKey(ho.getAddress()))
         * bufferCounts.put(ho.getAddress(),
         * (bufferCounts.get(ho.getAddress()+temp))/2); else
         * bufferCounts.put(ho.getAddress(), temp); } }
         */
    }

    /**
     * Prints a snapshot of the average buffer occupancy
     *
     * @param hosts The list of hosts in the simulation
     */
    private void printLine(List<DTNHost> hosts) {
        /**
         * double bufferOccupancy = 0.0; double bo2 = 0.0;
         *
         * for (DTNHost h : hosts) { double tmp = h.getBufferOccupancy(); tmp =
         * (tmp<=100.0)?(tmp):(100.0); bufferOccupancy += tmp; bo2 +=
         * (tmp*tmp)/100.0; }
         *
         * double E_X = bufferOccupancy / hosts.size(); double Var_X = bo2 /
         * hosts.size() - (E_X*E_X)/100.0;
         *
         * String output = format(SimClock.getTime()) + " " + format(E_X) + " "
         * + format(Var_X); write(output);
         */
        for (DTNHost h : hosts) {
            double temp = h.getBufferOccupancy();
            //temp = (temp <= 100.0) ? (temp) : (100.0);
            /**if (bufferCountsTimes.containsKey(h)) {
                //bufferCounts.put(h, (bufferCounts.get(h)+temp)/2); seems WRONG
                ArrayList<Double> bufferList = bufferCountsTimes.get(h);
                bufferList.add(temp);
                bufferCounts.put(h, bufferCounts.get(h));
                //write (""+ bufferCounts.get(h));
            } else {
                ArrayList<Double> bufferList = new ArrayList<Double>();
                bufferList.add(temp);
                **/
                bufferCounts.put(h, temp);
                //write (""+ bufferCounts.get(h));
            }
        }

    

    @Override
    public void done() {
        String statsText = "\n\n";
        for (Map.Entry<DTNHost, ArrayList<Double>> entry : bufferCountsTimes.entrySet()) {
            DTNHost host = entry.getKey();
            Integer val = host.getAddress();
            statsText = statsText + "\n\n" + Integer.toString(val)+" ";
            ArrayList<Double> whereList = entry.getValue();
            for (double bList : whereList) {
                statsText = statsText + " " + Double.toString(bList) + " ";
            }
        }
        write(statsText);
        super.done();
    }

}
