package com.jtrac.serverconfig;

/** Who runs the PostgreSQL server. */
public enum DatabaseMode {
    /** JTRAC starts its bundled PostgreSQL, bound to the loopback interface. */
    MANAGED,
    /** The operator provides PostgreSQL 18; JTRAC only connects to it. */
    EXTERNAL
}
