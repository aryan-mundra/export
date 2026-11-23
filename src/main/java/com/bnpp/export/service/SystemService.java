package com.bnpp.export.service;

import com.bnpp.export.dto.SystemInfoResponse;

/** Facts about the running module that screens show. */
public interface SystemService {

    /** Which database the module runs on. */
    SystemInfoResponse info();
}
