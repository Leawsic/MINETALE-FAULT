package cn.jehorstudio.minetale.battle.logic.actor.states.types;

// 只持有 BULLET 专属字段；空间、碰撞、外观与来源仍由 Actor 根状态负责。
public final class BulletStateCache {
    private double damage;
    private boolean damagesPlayer;

    private boolean displayGlobally;
    private int remainingRebounds;

    private double spinDegPerSecond;

    public double damage() {
        return this.damage;
    }

    public void setDamage(double damage) {
        if (!Double.isFinite(damage)) {
            throw new IllegalArgumentException("damage must be finite.");
        }
        this.damage = Math.max(0.0D, damage);
    }

    public boolean damagesPlayer() {
        return this.damagesPlayer;
    }

    public void setDamagesPlayer(boolean damagesPlayer) {
        this.damagesPlayer = damagesPlayer;
    }

    public boolean displayGlobally() {
        return this.displayGlobally;
    }

    public void setDisplayGlobally(boolean displayGlobally) {
        this.displayGlobally = displayGlobally;
    }

    public int remainingRebounds() {
        return this.remainingRebounds;
    }

    public void setRemainingRebounds(int remainingRebounds) {
        this.remainingRebounds = Math.max(0, remainingRebounds);
    }

    public double spinDegPerSecond() {
        return this.spinDegPerSecond;
    }

    public void setSpinDegPerSecond(double spinDegPerSecond) {
        if (!Double.isFinite(spinDegPerSecond)) {
            throw new IllegalArgumentException("spinDegPerSecond must be finite.");
        }
        this.spinDegPerSecond = spinDegPerSecond;
    }

    public BulletStateSnapshot snapshot() {
        return new BulletStateSnapshot(
                this.damage,
                this.damagesPlayer,
                this.displayGlobally,
                this.remainingRebounds,
                this.spinDegPerSecond
        );
    }
}
