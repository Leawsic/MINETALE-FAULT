package cn.jehorstudio.minetale.battle.logic.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LogicEventListener {
    LogicEvents value();

    int priority() default 0;
}
