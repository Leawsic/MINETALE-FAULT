package cn.jehorstudio.minetale.battle.presentation.states;

public final class InputStateCache {
    private boolean moveNorthHeld;
    private boolean moveSouthHeld;
    private boolean moveWestHeld;
    private boolean moveEastHeld;
    private boolean moveRiseHeld;
    private boolean moveFallHeld;

    private boolean turnLeftActive;
    private boolean turnRightActive;
    private boolean turnUpActive;
    private boolean turnDownActive;

    public boolean isMoveNorthHeld() {
        return this.moveNorthHeld;
    }

    public void setMoveNorthHeld(boolean moveNorthHeld) {
        this.moveNorthHeld = moveNorthHeld;
    }

    public boolean isMoveSouthHeld() {
        return this.moveSouthHeld;
    }

    public void setMoveSouthHeld(boolean moveSouthHeld) {
        this.moveSouthHeld = moveSouthHeld;
    }

    public boolean isMoveWestHeld() {
        return this.moveWestHeld;
    }

    public void setMoveWestHeld(boolean moveWestHeld) {
        this.moveWestHeld = moveWestHeld;
    }

    public boolean isMoveEastHeld() {
        return this.moveEastHeld;
    }

    public void setMoveEastHeld(boolean moveEastHeld) {
        this.moveEastHeld = moveEastHeld;
    }

    public boolean isMoveRiseHeld() {
        return this.moveRiseHeld;
    }

    public void setMoveRiseHeld(boolean moveRiseHeld) {
        this.moveRiseHeld = moveRiseHeld;
    }

    public boolean isMoveFallHeld() {
        return this.moveFallHeld;
    }

    public void setMoveFallHeld(boolean moveFallHeld) {
        this.moveFallHeld = moveFallHeld;
    }

    public boolean isTurnLeftActive() {
        return this.turnLeftActive;
    }

    public void setTurnLeftActive(boolean turnLeftActive) {
        this.turnLeftActive = turnLeftActive;
    }

    public boolean isTurnRightActive() {
        return this.turnRightActive;
    }

    public void setTurnRightActive(boolean turnRightActive) {
        this.turnRightActive = turnRightActive;
    }

    public boolean isTurnUpActive() {
        return this.turnUpActive;
    }

    public void setTurnUpActive(boolean turnUpActive) {
        this.turnUpActive = turnUpActive;
    }

    public boolean isTurnDownActive() {
        return this.turnDownActive;
    }

    public void setTurnDownActive(boolean turnDownActive) {
        this.turnDownActive = turnDownActive;
    }

    public void clearAll() {
        this.moveNorthHeld = false;
        this.moveSouthHeld = false;
        this.moveWestHeld = false;
        this.moveEastHeld = false;
        this.moveRiseHeld = false;
        this.moveFallHeld = false;
        this.turnLeftActive = false;
        this.turnRightActive = false;
        this.turnUpActive = false;
        this.turnDownActive = false;
    }

}
