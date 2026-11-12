package dev.skeletonkey.domain.validator;

/**
 * Port listing the Plugin_Handler bean names available at publish time. The Spring adapter
 * (task 7.3) implements it over the registered ActionHandler beans.
 */
@FunctionalInterface
public interface HandlerCatalog {

    /** True when a plugin handler is registered under this bean name. */
    boolean contains(String handlerName);

    /** Catalog with no handlers; any handler reference is reported as unknown. */
    static HandlerCatalog empty() {
        return name -> false;
    }
}
