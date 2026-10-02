package cn.jehorstudio.minetale.battle.logic.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class LogicEventListenerRegistry {
    private final EnumMap<LogicEvents, List<ListenerMethod>> listeners = new EnumMap<>(LogicEvents.class);

    public void register(Object listener) {
        Objects.requireNonNull(listener, "listener");

        for (Method method : listener.getClass().getMethods()) {
            LogicEventListener annotation = method.getAnnotation(LogicEventListener.class);
            if (annotation == null) {
                continue;
            }

            validateListenerMethod(listener, method);
            makeListenerMethodAccessible(listener, method);
            this.listeners
                    .computeIfAbsent(annotation.value(), ignored -> new ArrayList<>())
                    .add(new ListenerMethod(listener, method, annotation.priority()));
        }

        sortListeners();
    }

    public void dispatch(LogicEvents event) {
        List<ListenerMethod> eventListeners = this.listeners.get(event);
        if (eventListeners == null) {
            return;
        }

        for (ListenerMethod listener : eventListeners) {
            listener.invoke();
        }
    }

    private static void validateListenerMethod(Object listener, Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers)) {
            throw invalid(listener, method, "must be public.");
        }
        if (Modifier.isStatic(modifiers)) {
            throw invalid(listener, method, "must not be static.");
        }
        if (method.getParameterCount() != 0) {
            throw invalid(listener, method, "must have no parameters.");
        }
        if (method.getReturnType() != Void.TYPE) {
            throw invalid(listener, method, "must return void.");
        }
    }

    private static void makeListenerMethodAccessible(Object listener, Method method) {
        if (!method.trySetAccessible()) {
            throw invalid(listener, method, "must be accessible to the battle event registry.");
        }
    }

    private static IllegalArgumentException invalid(Object listener, Method method, String reason) {
        return new IllegalArgumentException(
                "Invalid battle event listener %s#%s: %s".formatted(
                        listener.getClass().getName(),
                        method.getName(),
                        reason
                )
        );
    }

    private void sortListeners() {
        for (List<ListenerMethod> eventListeners : this.listeners.values()) {
            eventListeners.sort(Comparator.comparingInt(ListenerMethod::priority).reversed());
        }
    }

    private record ListenerMethod(Object target, Method method, int priority) {
        private void invoke() {
            try {
                this.method.invoke(this.target);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Battle event listener is not accessible.", exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Battle event listener threw an exception.", cause);
            }
        }
    }
}
