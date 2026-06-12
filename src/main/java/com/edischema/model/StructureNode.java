package com.edischema.model;

import java.util.List;

/**
 * A node in the transaction-set structure tree (heading/detail/summary areas).
 * Sequence order of children always matches the order on the Stedi page.
 */
public sealed interface StructureNode permits StructureNode.SegmentUse, StructureNode.LoopUse {

    /**
     * A segment occurrence within the transaction structure.
     *
     * @param position  Stedi position number, e.g. "010"
     * @param code      segment code, e.g. BEG
     * @param name      segment name
     * @param mandatory Mandatory -> minOccurs=1, otherwise 0
     * @param maxUse    max use; -1 means "&gt;1" (unbounded) on the website
     */
    record SegmentUse(String position, String code, String name,
                      boolean mandatory, int maxUse) implements StructureNode {
    }

    /**
     * A loop within the transaction structure. Children may contain nested loops.
     *
     * @param code      generated loop code, e.g. N1_LOOP (lead segment code + "_LOOP")
     * @param name      loop display name on the website, e.g. "N1 Loop"
     * @param mandatory Mandatory -> minOccurs=1
     * @param repeat    repeat count; -1 means "&gt;1" (unbounded) on the website
     */
    record LoopUse(String code, String name, boolean mandatory, int repeat,
                   List<StructureNode> children) implements StructureNode {
    }
}
