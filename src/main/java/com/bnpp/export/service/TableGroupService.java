package com.bnpp.export.service;

import com.bnpp.export.dto.GroupTableResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.ResolvedGroup;

import java.util.List;

/** The table groups of config/table-groups.yml, checked against the database. */
public interface TableGroupService {

    /** Every group in file order, with its tables as found in the database. Fails listing every missing table or column. */
    List<ResolvedGroup> groups();

    /** Every table of every group (root first), for the list builder. */
    List<GroupTableResponse> tables();

    /** The groups a list's columns use, in file order, each with the tables to read. */
    List<GroupSelection> selections(List<FormatField> fields);
}
