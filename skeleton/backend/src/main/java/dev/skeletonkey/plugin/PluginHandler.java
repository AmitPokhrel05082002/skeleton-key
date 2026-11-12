package dev.skeletonkey.plugin;

/**
 * Extension point for app-specific logic (Req 13.1). Implementations are Spring beans; the bean name is the handler
 * name referenced from an app definition ({@code action.handler}, AUTO step {@code handler}, HANDLER events).
 */
@FunctionalInterface
public interface PluginHandler {

    /** Returns the field changes to apply, or {@link HandlerResult#none()}. */
    HandlerResult handle(HandlerContext ctx);
}
