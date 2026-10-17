package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;

import java.util.Objects;

final class Supplies {
    private Supplies() {}

    static boolean matches(ProfileStore.Target target, ItemStack stack) {
        if (ItemStack.isEmpty(stack) || !target.itemId().equals(stack.getItemId())
                || target.quality() != stack.getQualityIndex()) return false;
        BsonDocument expected = target.metadata() == null ? null : BsonDocument.parse(target.metadata());
        BsonDocument actual = metadata(stack);
        if (expected != null && expected.isEmpty()) expected = null;
        return Objects.equals(expected, actual);
    }

    /** Whole metadata document, or null when empty. The engine has no non-deprecated full-document read. */
    @SuppressWarnings("deprecation")
    static BsonDocument metadata(ItemStack stack) {
        var metadata = stack.getMetadata();
        return metadata == null || metadata.isEmpty() ? null : metadata;
    }
}
