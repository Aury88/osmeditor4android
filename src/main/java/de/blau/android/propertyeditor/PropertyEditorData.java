package de.blau.android.propertyeditor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.acra.ACRA;

import android.util.Log;
import de.blau.android.osm.OsmElement;
import de.blau.android.osm.Relation;
import de.blau.android.osm.RelationMember;
import de.blau.android.osm.RelationMemberDescription;

/**
 * Holds data sent in intents. Directly serializing a TreeMap in an intent does not work, as it comes out as a HashMap
 * (?!?)
 * 
 * @author Jan
 */
public class PropertyEditorData implements Serializable {
    private static final long serialVersionUID = 3L;

    private static final String DEBUG_TAG = PropertyEditorData.class.getSimpleName();

    public final long                                 osmId;
    public final String                               type;
    public final LinkedHashMap<String, String>        tags;
    public final LinkedHashMap<String, String>        originalTags;
    public final HashMap<Long, String>                parents;         // just store the ids and role
    public final HashMap<Long, String>                originalParents; // just store the ids and role
    public final ArrayList<RelationMemberDescription> members;
    public final ArrayList<RelationMemberDescription> originalMembers;

    public String focusOnKey = null;

    public PropertyEditorData(long osmId, String type, Map<String, String> tags, Map<String, String> originalTags, HashMap<Long, String> parents,
            HashMap<Long, String> originalParents, ArrayList<RelationMemberDescription> members, ArrayList<RelationMemberDescription> originalMembers) {
        this(osmId, type, tags, originalTags, parents, originalParents, members, originalMembers, null);
    }

    private PropertyEditorData(long osmId, String type, Map<String, String> tags, Map<String, String> originalTags, HashMap<Long, String> parents,
            HashMap<Long, String> originalParents, ArrayList<RelationMemberDescription> members, ArrayList<RelationMemberDescription> originalMembers,
            String focusOnKey) {
        this.osmId = osmId;
        this.type = type;
        this.tags = tags != null ? new LinkedHashMap<>(tags) : null;
        this.originalTags = originalTags != null ? new LinkedHashMap<>(originalTags) : null;
        this.parents = parents;
        this.originalParents = originalParents;
        this.members = members;
        this.originalMembers = originalMembers;
        this.focusOnKey = focusOnKey;
    }

    public PropertyEditorData(OsmElement selectedElement, String focusOnKey) {
        osmId = selectedElement.getOsmId();
        type = selectedElement.getName();
        tags = new LinkedHashMap<>(selectedElement.getTags());
        originalTags = tags;
        HashMap<Long, String> tempParents = new HashMap<>();
        if (selectedElement.getParentRelations() != null) {
            for (Relation r : selectedElement.getParentRelations()) {
                RelationMember rm = r.getMember(selectedElement);
                if (rm != null) {
                    tempParents.put(r.getOsmId(), rm.getRole());
                } else {
                    Log.e(DEBUG_TAG, "inconsistency in relation membership");
                    ACRA.getErrorReporter().putCustomData("STATUS", "NOCRASH");
                    ACRA.getErrorReporter().handleException(null);
                }
            }
            parents = tempParents;
            originalParents = parents;
        } else {
            parents = null;
            originalParents = null;
        }
        ArrayList<RelationMemberDescription> tempMembers = new ArrayList<>();
        if (selectedElement.getName().equals(Relation.NAME)) {
            for (RelationMember rm : ((Relation) selectedElement).getMembers()) {
                RelationMemberDescription newRm = new RelationMemberDescription(rm);
                tempMembers.add(newRm);
            }
            members = tempMembers;
            originalMembers = members;
        } else {
            members = null;
            originalMembers = null;
        }

        this.focusOnKey = focusOnKey;
    }

    public static PropertyEditorData[] deserializeArray(Serializable s) {
        Object[] a = (Object[]) s;
        PropertyEditorData[] r = new PropertyEditorData[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (PropertyEditorData) a[i];
        }
        return r;
    }
}
