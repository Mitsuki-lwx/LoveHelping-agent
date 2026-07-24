package cn.lwx.lwxaiagent.agent;

import lombok.Data;

/**
 * @description: ReActAgent is an abstract class that defines the basic structure and behavior of a ReActAgent.
 * It contains think() method and act() method, used for thinking and acting respectively. ReActAgent also extends BaseAgent, providing implementations of run() and step() methods.
 *
 */
@Data
public abstract class ReActAgent extends BaseAgent {
    /**
     * @description: think() method executes the thinking logic.
     * @return boolean
     *
     * @return
     */
    public abstract boolean think();

    /**
     * @description: act() method executes the action logic.
     * @return
     */
    public abstract String act();
    @Override
    public String step() {
        try{
            boolean shouldAct = think();
            if (shouldAct) {
                return act();
            } else {
                // If no action needed, return a default value or empty string
                return " thinking completed, no action needed.";
            }
        }catch (Exception e){
            // Handle exception, log etc.
            e.printStackTrace();
            return "Error: "+ e.getMessage();
        }


    }
}
