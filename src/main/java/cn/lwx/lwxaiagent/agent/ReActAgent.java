package cn.lwx.lwxaiagent.agent;

import lombok.Data;

/**
 * @description: ReActAgent是一个抽象类，定义了ReActAgent的基本结构和行为。
 * 它包含了think()方法和act()方法，分别用于思考和行动。ReActAgent还实现了BaseAgent接口，提供了run()和step()方法的实现。
 *
 */
@Data
public abstract class ReActAgent extends BaseAgent {
    /**
     * @description: think()方法用于执行思考逻辑。
     * @return boolean
     *
     * @return
     */
    public abstract boolean think();

    /**
     * @description: act()方法用于执行行动逻辑。
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
                //如果不需要行动，可以返回一个默认值或空字符串
                return " thinking completed, no action needed.";
            }
        }catch (Exception e){
            //处理异常，记录日志等
            e.printStackTrace();
            return "Error: "+ e.getMessage();
        }


    }
}
