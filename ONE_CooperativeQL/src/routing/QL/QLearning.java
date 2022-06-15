package routing.QL;

//Catalano Machine Learning Library
//The Catalano Framework
//
//Copyright © Diego Catalano, 2013
//diego.catalano at live.com
//
//Copyright © Andrew Kirillov, 2007-2008
//andrew.kirillov@gmail.com
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
//

import java.util.Random;

/**
 * The class provides implementation of Q-Learning algorithm, known as
 * off-policy Temporal Difference control.
 * 
 * @author Diego Catalano
 */
public class QLearning {
	// amount of possible states
	private int states;
	// amount of possible actions
	private int actions;
	// q-values
	private double[][] qvalues;
	// exploration policy
	private IExplorationPolicy explorationPolicy;

	// discount factor
	private double discountFactor = 0.2;
	// learning rate
	private double learningRate = 0.25;

	private boolean[][] actionRestriction;
	private double visitSA[][];

	/**
	 * Amount of possible states.
	 * 
	 * @return States
	 */
	public int getStates() {
		return states;
	}

	/**
	 * Amount of possible actions.
	 * 
	 * @return Actions
	 */
	public int getActions() {
		return actions;
	}

	/**
	 * Exploration policy.
	 * 
	 * @return Exploration Policy
	 */
	public IExplorationPolicy getExplorationPolicy() {
		return explorationPolicy;
	}

	/**
	 * Policy, which is used to select actions.
	 * 
	 * @param explorationPolicy Exploration Policy
	 */
	public void setExplorationPolicy(IExplorationPolicy explorationPolicy) {
		this.explorationPolicy = explorationPolicy;
	}

	/**
	 * Get Learning Rate
	 * 
	 * @return Learning Rate
	 */
	public double getLearningRate() {
		return learningRate;
	}

	/**
	 * Learning rate, [0, 1]. The value determines the amount of updates Q-function
	 * receives during learning. The greater the value, the more updates the
	 * function receives. The lower the value, the less updates it receives.
	 * 
	 * @param learningRate
	 */
	public void setLearningRate(double learningRate) {
		this.learningRate = Math.max(0.0, Math.min(1.0, learningRate));
	}

	/**
	 * Get Discount factor for the expected summary reward.
	 * 
	 * @return Discount Factor
	 */
	public double getDiscountFactor() {
		return discountFactor;
	}

	/**
	 * Discount factor for the expected summary reward. The value serves as
	 * multiplier for the expected reward. So if the value is set to 1, then the
	 * expected summary reward is not discounted. If the value is getting smaller,
	 * then smaller amount of the expected reward is used for actions' estimates
	 * update.
	 * 
	 * @param discountFactor
	 */
	public void setDiscountFactor(double discountFactor) {
		this.discountFactor = Math.max(0.0, Math.min(1.0, discountFactor));
	}

	/**
	 * Initializes a new instance of the QLearning class.
	 * 
	 * @param states            Amount of possible states.
	 * @param actions           Amount of possible actions.
	 * @param explorationPolicy Exploration policy. Aturan action selection.
	 * @param randomize         Randomize action estimates or not.
	 */
	public QLearning(int states, int actions, IExplorationPolicy explorationPolicy, boolean randomize,
			boolean[][] actionRestriction) {
		this.states = states;
		this.actions = actions;
		this.explorationPolicy = explorationPolicy;
		this.actionRestriction = actionRestriction;
		this.visitSA = new double[states][actions];
		// create Q-array (initialize qvalue = 0 for each s & a)
		qvalues = new double[states][];
		for (int i = 0; i < states; i++) {
			qvalues[i] = new double[actions];
		}

		// do randomization
		if (randomize) {
			Random r = new Random();

			for (int i = 0; i < states; i++) {
				for (int j = 0; j < actions; j++) {
					qvalues[i][j] = r.nextDouble() / 10;
				}
			}
		}
	}

	/** set qlearning with previous knowledge */
	public QLearning(int states, int actions, IExplorationPolicy explorationPolicy, double[][] QV,
			boolean[][] actionRestriction) {
		this.states = states;
		this.actions = actions;
		this.explorationPolicy = explorationPolicy;
		this.actionRestriction = actionRestriction;
		this.visitSA = new double[states][actions];
		// create Q-array (initialize qvalue = 0 for each s & a)
		qvalues = new double[states][];
		for (int i = 0; i < states; i++) {
			qvalues[i] = new double[actions];
		}

		for (int i = 0; i < states; i++) {
			for (int j = 0; j < actions; j++) {
				qvalues[i][j] = QV[i][j];
			}
		}

	}

	/**
	 * Get next action from the specified state.
	 * 
	 * @param state Current state to get an action for.
	 * @return Returns the action for the state.
	 */
	public int GetAction(int state) {
		int action = explorationPolicy.ChooseAction(qvalues[state], actionRestriction[state]);
		visitSA[state][action] += 1;
		return action;
	}

	/**
	 * Update Q-function's value for the previous state-action pair.
	 * 
	 * @param previousState Previous state.
	 * @param action        Action, which leads from previous to the next state.
	 * @param reward        Reward value, received by taking specified action from
	 *                      previous state.
	 * @param nextState     Next state.
	 */
	public void UpdateState(int previousState, int action, double reward, int nextState) {
		// next state's action estimations
		double[] nextActionEstimations = qvalues[nextState];
		// find maximum expected summary reward from the next state
		double maxNextExpectedReward = nextActionEstimations[0];

		for (int i = 1; i < actions; i++) {
			if (nextActionEstimations[i] > maxNextExpectedReward)
				maxNextExpectedReward = nextActionEstimations[i];
		}

		// previous state's action estimations
		double[] previousActionEstimations = qvalues[previousState];
		learningRate = 1.0 / (1.0 + visitSA[previousState][action]);
		// update expexted summary reward of the previous state
		previousActionEstimations[action] *= (1.0 - learningRate);
		previousActionEstimations[action] += (learningRate * (reward + discountFactor * maxNextExpectedReward));
	}

	public double[][] getqvalues() {
		return qvalues;
	}
}
