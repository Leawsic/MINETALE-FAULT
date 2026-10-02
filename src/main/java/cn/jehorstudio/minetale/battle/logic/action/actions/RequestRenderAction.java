package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequest;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

// 首次运行只提交一次表现请求，随后以请求时长阻塞当前 Action Stack；其他 Stack 不受此等待影响。
public final class RequestRenderAction implements BattleAction {
    private final BattleRenderRequest request;
    private WaitSecondsAction durationWait;

    public RequestRenderAction(BattleRenderRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    public BattleRenderRequest request() {
        return this.request;
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (this.durationWait == null) {
            context.instance().requestRenderAction(this.request);
            this.durationWait = new WaitSecondsAction(this.request.durationSeconds());
        }
        return this.durationWait.run(context);
    }
}
