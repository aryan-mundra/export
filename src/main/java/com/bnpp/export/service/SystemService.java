package com.bnpp.export.service;

import com.bnpp.export.dto.DatabaseSwitchRequest;
import com.bnpp.export.dto.DatabaseSwitchResponse;
import com.bnpp.export.dto.SystemInfoResponse;

/** Facts about the running module that screens show, and the Database switch. */
public interface SystemService {

    /** Which database the module runs on, and the saved PostgreSQL connection (without its password). */
    SystemInfoResponse info();

    /**
     * Saves the chosen database and restarts the module on it. A PostgreSQL connection is tried first, and the
     * module's ver_export_ tables must exist there; otherwise nothing changes and the reason is returned (400).
     * Refused (409) while an export is running.
     */
    DatabaseSwitchResponse switchDatabase(DatabaseSwitchRequest request);
}
