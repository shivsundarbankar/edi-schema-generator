package com.edischema.model;

import java.util.List;

/**
 * The parsed transaction set page: ordered structure of all three areas.
 */
public record TransactionSetDoc(String id, String name, String releaseCode,
                                List<StructureNode> heading,
                                List<StructureNode> detail,
                                List<StructureNode> summary) {

    public List<StructureNode> allAreasInOrder() {
        return java.util.stream.Stream.of(heading, detail, summary)
                .flatMap(List::stream)
                .toList();
    }
}
