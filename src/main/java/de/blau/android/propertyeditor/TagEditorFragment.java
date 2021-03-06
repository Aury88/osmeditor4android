package de.blau.android.propertyeditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AlertDialog.Builder;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils.TruncateAt;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import de.blau.android.App;
import de.blau.android.HelpViewer;
import de.blau.android.R;
import de.blau.android.exception.UiStateException;
import de.blau.android.javascript.EvalCallback;
import de.blau.android.names.Names;
import de.blau.android.names.Names.NameAndTags;
import de.blau.android.osm.OsmElement;
import de.blau.android.osm.OsmElement.ElementType;
import de.blau.android.osm.Server;
import de.blau.android.osm.Tags;
import de.blau.android.prefs.Preferences;
import de.blau.android.presets.Preset;
import de.blau.android.presets.Preset.PresetElement;
import de.blau.android.presets.Preset.PresetGroup;
import de.blau.android.presets.Preset.PresetItem;
import de.blau.android.presets.Preset.PresetKeyType;
import de.blau.android.presets.Preset.ValueType;
import de.blau.android.presets.PresetElementPath;
import de.blau.android.presets.ValueWithCount;
import de.blau.android.util.BaseFragment;
import de.blau.android.util.ClipboardUtils;
import de.blau.android.util.KeyValue;
import de.blau.android.util.SavingHelper;
import de.blau.android.util.Snack;
import de.blau.android.util.StreetTagValueAdapter;
import de.blau.android.util.StringWithDescription;
import de.blau.android.util.Util;
import de.blau.android.views.CustomAutoCompleteTextView;

public class TagEditorFragment extends BaseFragment implements PropertyRows, EditorUpdate {
    private static final String DEBUG_TAG = TagEditorFragment.class.getSimpleName();

    private static final String RECENTPRESETS_FRAGMENT = "recentpresets_fragment";

    private static final String SAVEDTAGS_KEY = "SAVEDTAGS";

    private static final String ELEMENTS_KEY = "elements";

    private static final String DISPLAY_MR_UPRESETS = "displayMRUpresets";

    private static final String FOCUS_ON_KEY = "focusOnKey";

    private static final String APPLY_LAST_ADDRESS_TAGS = "applyLastAddressTags";

    private static final String EXTRA_TAGS = "extraTags";

    private static final String PRESETSTOAPPLY_KEY = "presetsToApply";

    private static final String TAGS_KEY = "tags";

    private SavingHelper<LinkedHashMap<String, String>> savingHelper = new SavingHelper<>();

    private static SelectedRowsActionModeCallback tagSelectedActionModeCallback = null;
    private static final Object                   actionModeCallbackLock        = new Object();

    private Names names = null;

    private boolean      loaded   = false;
    private String[]     types;
    private long[]       osmIds;
    private Preferences  prefs    = null;
    private OsmElement[] elements = null;

    private LayoutInflater inflater = null;

    private NameAdapters nameAdapters = null;

    /**
     * saves any changed fields on onPause
     */
    private LinkedHashMap<String, ArrayList<String>> savedTags = null;

    /**
     * per tag preset association
     */
    private HashMap<String, PresetItem> tags2Preset = new HashMap<>();

    /**
     * Best matching preset
     */
    private PresetItem primaryPresetItem = null;

    /**
     * further matching presets
     */
    private ArrayList<PresetItem> secondaryPresets = new ArrayList<>();

    /**
     * selective copy of tags
     */
    Map<String, String> copiedTags = null;

    private FormUpdate formUpdate;

    private PresetFilterUpdate presetFilterUpdate;

    private PropertyEditorListener propertyEditorListener;

    private int maxStringLength; // maximum key, value and role length

    /**
     * Interface for handling the key:value pairs in the TagEditor.
     * 
     * @author Andrew Gregory
     */
    interface KeyValueHandler {
        void handleKeyValue(final EditText keyEdit, final EditText valueEdit, final ArrayList<String> tagValues);
    }

    /**
     * Perform some processing for each key:value pair in the TagEditor.
     * 
     * @param handler The handler that will be called for each key:value pair.
     */
    private void processKeyValues(final KeyValueHandler handler) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        processKeyValues(rowLayout, handler);
    }

    /**
     * Perform some processing for each key:value pair in the TagEditor.
     * 
     * @param handler The handler that will be called for each key:value pair.
     */
    private void processKeyValues(LinearLayout rowLayout, final KeyValueHandler handler) {
        final int size = rowLayout.getChildCount();
        for (int i = 0; i < size; ++i) {
            View view = rowLayout.getChildAt(i);
            TagEditRow row = (TagEditRow) view;
            handler.handleKeyValue(row.keyEdit, row.valueEdit, row.tagValues);
        }
    }

    /**
     * @param applyLastAddressTags
     * @param focusOnKey
     * @param displayMRUpresets
     */
    static public TagEditorFragment newInstance(OsmElement[] elements, ArrayList<LinkedHashMap<String, String>> tags, boolean applyLastAddressTags,
            String focusOnKey, boolean displayMRUpresets, HashMap<String, String> extraTags, ArrayList<PresetElementPath> presetsToApply) {
        TagEditorFragment f = new TagEditorFragment();

        Bundle args = new Bundle();

        args.putSerializable(ELEMENTS_KEY, elements);
        args.putSerializable(TAGS_KEY, tags);
        args.putSerializable(APPLY_LAST_ADDRESS_TAGS, applyLastAddressTags);
        args.putSerializable(FOCUS_ON_KEY, focusOnKey);
        args.putSerializable(DISPLAY_MR_UPRESETS, displayMRUpresets);
        args.putSerializable(EXTRA_TAGS, extraTags);
        args.putSerializable(PRESETSTOAPPLY_KEY, presetsToApply);

        f.setArguments(args);
        // f.setShowsDialog(true);

        return f;
    }

    @Override
    public void onAttachToContext(Context context) {
        Log.d(DEBUG_TAG, "onAttachToContext");
        try {
            nameAdapters = (NameAdapters) context;
            formUpdate = (FormUpdate) context;
            presetFilterUpdate = (PresetFilterUpdate) context;
            propertyEditorListener = (PropertyEditorListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement PropertyEditorListener,  NameAdapters, FormUpdate and PresetFilterUpdate");
        }
        setHasOptionsMenu(true);
        getActivity().supportInvalidateOptionsMenu();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(DEBUG_TAG, "onCreate");
    }

    /**
     * display member elements of the relation if any
     * 
     * @param members
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView rowLayout = null;

        boolean applyLastAddressTags = false;
        String focusOnKey = null;
        boolean displayMRUpresets = false;

        if (savedInstanceState == null) {
            // No previous state to restore - get the state from the intent
            Log.d(DEBUG_TAG, "Initializing from original arguments");
            elements = (OsmElement[]) getArguments().getSerializable(ELEMENTS_KEY);
            applyLastAddressTags = (Boolean) getArguments().getSerializable(APPLY_LAST_ADDRESS_TAGS);
            focusOnKey = (String) getArguments().getSerializable(FOCUS_ON_KEY);
            displayMRUpresets = (Boolean) getArguments().getSerializable(DISPLAY_MR_UPRESETS);
        } else {
            // Restore activity from saved state
            Log.d(DEBUG_TAG, "Restoring from savedInstanceState");
            Object[] tempElements = (Object[]) savedInstanceState.getSerializable(ELEMENTS_KEY);
            elements = new OsmElement[tempElements.length];
            for (int i = 0; i < tempElements.length; i++) {
                elements[i] = (OsmElement) tempElements[i];
            }
            @SuppressWarnings("unchecked")
            Map<String, ArrayList<String>> temp = (Map<String, ArrayList<String>>) savedInstanceState.getSerializable(SAVEDTAGS_KEY);
            savedTags = new LinkedHashMap<>();
            savedTags.putAll(temp);
        }

        prefs = new Preferences(getActivity());

        if (prefs.getEnableNameSuggestions()) {
            names = App.getNames(getActivity());
        }

        Server server = prefs.getServer();
        maxStringLength = server.getCachedCapabilities().getMaxStringLength();

        this.inflater = inflater;
        rowLayout = (ScrollView) inflater.inflate(R.layout.taglist_view, container, false);

        LinearLayout editRowLayout = (LinearLayout) rowLayout.findViewById(R.id.edit_row_layout);
        // editRowLayout.setSaveFromParentEnabled(false);
        editRowLayout.setSaveEnabled(false);

        types = new String[elements.length];
        osmIds = new long[elements.length];
        for (int i = 0; i < elements.length; i++) {
            types[i] = elements[i].getName();
            osmIds[i] = elements[i].getOsmId();
        }

        LinkedHashMap<String, ArrayList<String>> tags;
        if (savedTags != null) { // view was destroyed and needs to be recreated with current state
            Log.d(DEBUG_TAG, "Restoring from instance variable");
            tags = savedTags;
        } else {
            tags = buildEdits();
        }

        // Log.d(DEBUG_TAG,"element " + element + " tags " + tags);

        loaded = false;
        // rowLayout.removeAllViews();
        for (Entry<String, ArrayList<String>> pair : tags.entrySet()) {
            insertNewEdit(editRowLayout, pair.getKey(), pair.getValue(), -1);
        }

        loaded = true;
        TagEditRow row = ensureEmptyRow(editRowLayout);

        if (row != null && getUserVisibleHint()) { // don't request focus if we are not visible
            Log.d(DEBUG_TAG, "is visible");
            row.keyEdit.requestFocus();
            row.keyEdit.dismissDropDown();

            if (focusOnKey != null) {
                focusOnValue(editRowLayout, focusOnKey);
            } else {
                focusOnEmptyValue(editRowLayout); // probably never actually works
            }
        }
        //
        if (applyLastAddressTags) {
            loadEdits(editRowLayout,
                    Address.predictAddressTags(getActivity(), getType(), getOsmId(),
                            ((StreetTagValueAdapter) nameAdapters.getStreetNameAdapter(null)).getElementSearch(), getKeyValueMap(editRowLayout, false),
                            Address.DEFAULT_HYSTERESIS));
            if (getUserVisibleHint()) {
                if (!focusOnValue(editRowLayout, Tags.KEY_ADDR_HOUSENUMBER)) {
                    focusOnValue(editRowLayout, Tags.KEY_ADDR_STREET);
                } // this could be a bit more refined
            }
        }

        // Add any extra tags that were supplied
        HashMap<String, String> extraTags = (HashMap<String, String>) getArguments().getSerializable(EXTRA_TAGS);
        if (extraTags != null) {
            for (Entry<String, String> e : extraTags.entrySet()) {
                addTag(editRowLayout, e.getKey(), e.getValue(), true, false);
            }
        }

        updateAutocompletePresetItem(editRowLayout, null, false); // set preset from initial tags

        if (displayMRUpresets) {
            Log.d(DEBUG_TAG, "Adding MRU prests");
            FragmentManager fm = getChildFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            Fragment recentPresetsFragment = fm.findFragmentByTag(RECENTPRESETS_FRAGMENT);
            if (recentPresetsFragment != null) {
                ft.remove(recentPresetsFragment);
            }

            recentPresetsFragment = RecentPresetsFragment.newInstance(elements[0]); // FIXME
            ft.add(R.id.tag_mru_layout, recentPresetsFragment, RECENTPRESETS_FRAGMENT);
            ft.commit();
        }

        CheckBox headerCheckBox = (CheckBox) rowLayout.findViewById(R.id.header_tag_selected);
        headerCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    selectAllRows();
                } else {
                    deselectAllRows();
                }
            }
        });
        Log.d(DEBUG_TAG, "onCreateView returning");
        return rowLayout;
    }

    /**
     * Build the data structure we use to build the edit display
     * 
     * @return
     */
    private LinkedHashMap<String, ArrayList<String>> buildEdits() {
        @SuppressWarnings("unchecked")
        ArrayList<LinkedHashMap<String, String>> originalTags = (ArrayList<LinkedHashMap<String, String>>) getArguments().getSerializable(TAGS_KEY);
        //
        LinkedHashMap<String, ArrayList<String>> tags = new LinkedHashMap<>();
        for (LinkedHashMap<String, String> map : originalTags) {
            for (Entry<String, String> entry : map.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (!tags.containsKey(key)) {
                    tags.put(key, new ArrayList<String>());
                }
                tags.get(key).add(value);
            }
        }
        // for those keys that don't have a value for each element add an empty string
        int l = originalTags.size();
        for (List<String> v : tags.values()) {
            if (v.size() != l) {
                v.add("");
            }
        }
        return tags;
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(DEBUG_TAG, "onStart");
        // the following likely wont work in onCreateView
        @SuppressWarnings("unchecked")
        ArrayList<PresetElementPath> presetsToApply = (ArrayList<PresetElementPath>) getArguments().getSerializable(PRESETSTOAPPLY_KEY);
        Preset[] presets = App.getCurrentPresets(getActivity());
        PresetGroup rootGroup = presets[0].getRootGroup();
        if (presetsToApply != null) {
            for (PresetElementPath pp : presetsToApply) {
                PresetElement pi = Preset.getElementByPath(rootGroup, pp);
                if (pi != null && pi instanceof PresetItem) {
                    applyPreset((PresetItem) pi, false, true);
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(DEBUG_TAG, "onResume");
        formUpdate.tagsUpdated(); // kick the form
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(DEBUG_TAG, "onSaveInstanceState");
        outState.putSerializable(ELEMENTS_KEY, elements);
        outState.putSerializable(SAVEDTAGS_KEY, savedTags);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(DEBUG_TAG, "onPause");
        savedTags = getKeyValueMap(true);
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(DEBUG_TAG, "onStop");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(DEBUG_TAG, "onDestroy");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(DEBUG_TAG, "onDestroyView");
    }

    /**
     * Creates edit rows from a SortedMap containing tags (as sequential key-value pairs)
     * 
     * Backwards compatible version
     * 
     * @param tags map containing the tags
     */
    private void loadEditsSingle(final Map<String, String> tags) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        LinkedHashMap<String, ArrayList<String>> convertedTags = new LinkedHashMap<>();
        for (Entry<String, String> entry : tags.entrySet()) {
            ArrayList<String> v = new ArrayList<>();
            v.add(entry.getValue());
            convertedTags.put(entry.getKey(), v);
        }
        loadEdits(rowLayout, convertedTags);
    }

    /**
     * Creates edits from a SortedMap containing tags (as sequential key-value pairs)
     */
    private void loadEdits(final Map<String, ArrayList<String>> tags) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        loadEdits(rowLayout, tags);
    }

    /**
     * Creates edits from a SortedMap containing tags (as sequential key-value pairs)
     */
    private void loadEdits(LinearLayout rowLayout, final Map<String, ArrayList<String>> tags) {
        if (rowLayout == null) {
            Log.e(DEBUG_TAG, "loadEdits rowLayout null");
            return;
        }
        loaded = false;
        rowLayout.removeAllViews();
        for (Entry<String, ArrayList<String>> pair : tags.entrySet()) {
            insertNewEdit(rowLayout, pair.getKey(), pair.getValue(), -1);
        }
        loaded = true;
        ensureEmptyRow(rowLayout);
    }

    @Override
    public void updatePresets() {
        updateAutocompletePresetItem(null);
    }

    /**
     * Edits may change the best fitting preset
     * 
     * @param presetItem if null determine best preset from existing tags
     */
    void updateAutocompletePresetItem(@Nullable PresetItem presetItem) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        updateAutocompletePresetItem(rowLayout, presetItem, false);
    }

    /**
     * Edits may change the best fitting preset
     * 
     * @param presetItem if null determine best preset from existing tags
     * @param addToMru add to MRU if true
     */
    void updateAutocompletePresetItem(@Nullable PresetItem presetItem, boolean addToMru) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        updateAutocompletePresetItem(rowLayout, presetItem, addToMru);
    }

    /**
     * If tags have changed the autocomplete adapters need to be recalculated on what is the current preset
     * 
     * @param rowLayout the layout containing the tag rows
     * @param presetItem if null determine best preset from existing tags
     * @param addToMru add to MRU if true
     */
    private void updateAutocompletePresetItem(@Nullable LinearLayout rowLayout, @Nullable PresetItem presetItem, boolean addToMru) {
        Log.d(DEBUG_TAG, "setting new autocompletePresetItem");
        Preset[] presets = App.getCurrentPresets(getActivity());
        PresetItem savedPrimaryPresetItem = primaryPresetItem;
        List<PresetItem> savedSecondaryPresets = new ArrayList<>(getSecondaryPresets());

        clearPresets();
        clearSecondaryPresets();
        LinkedHashMap<String, String> allTags = getKeyValueMapSingle(rowLayout, true);

        if (presetItem == null) {
            primaryPresetItem = Preset.findBestMatch(presets, allTags, true); // FIXME multiselect;
        } else {
            primaryPresetItem = presetItem;
        }
        Map<String, String> nonAssigned = addPresetsToTags(primaryPresetItem, allTags);
        int nonAssignedCount = nonAssigned.size();
        while (nonAssignedCount > 0) {
            PresetItem nonAssignedPreset = Preset.findBestMatch(presets, nonAssigned, true);
            if (nonAssignedPreset == null) {
                // no point in continuing
                break;
            }
            addSecondaryPreset(nonAssignedPreset);
            nonAssigned = addPresetsToTags(nonAssignedPreset, (LinkedHashMap<String, String>) nonAssigned);
            nonAssignedCount = nonAssigned.size();
        }

        // update hints
        if (rowLayout != null) {
            for (int i = 0; i < rowLayout.getChildCount() - 1; i++) { // don't update empty row at end
                setHint((TagEditRow) rowLayout.getChildAt(i));
            }
        } else {
            Log.e(DEBUG_TAG, "updateAutocompletePresetItem called with null layout");
        }

        if (elements.length == 1) {
            // update element type for preset filter if necessary
            ElementType newType = elements[0].getType(allTags);
            ElementType oldType = elements[0].getType();
            if (newType != oldType) {
                presetFilterUpdate.typeUpdated(newType);
            }

            if (presets != null && addToMru) {
                if ((primaryPresetItem != null && !primaryPresetItem.equals(savedPrimaryPresetItem)) || !savedSecondaryPresets.equals(getSecondaryPresets())) {
                    List<PresetItem> items = new ArrayList<>(getSecondaryPresets());
                    items.add(primaryPresetItem);
                    for (PresetItem item : items) {
                        addToMru(presets, item);
                    }
                    ((PropertyEditor) getActivity()).recreateRecentPresetView();
                }
            }
        }
    }

    /**
     * Add item to most recently used list of preset items
     * 
     * @param presets
     * @param item
     */
    void addToMru(Preset[] presets, PresetItem item) {
        for (Preset p : presets) {
            if (p != null && p.contains(item)) {
                p.putRecentlyUsed(item);
                break;
            }
        }
    }

    private void setHint(TagEditRow row) {
        String aTagKey = row.getKey();
        PresetItem preset = getPreset(aTagKey);
        if (preset != null && aTagKey != null && !aTagKey.equals("")) { // set hints even if value isen't empty
            String hint = preset.getHint(aTagKey);
            if (hint != null) {
                row.valueEdit.setHint(hint);
            } else if (preset.getKeyType(aTagKey) != PresetKeyType.TEXT) {
                row.valueEdit.setHint(R.string.tag_autocomplete_value_hint);
            } else {
                row.valueEdit.setHint(R.string.tag_value_hint);
            }
            if (row.getValue().length() == 0) {
                String defaultValue = preset.getDefault(aTagKey);
                if (defaultValue != null) { //
                    row.valueEdit.setText(defaultValue);
                }
            }
            if (!row.same) {
                row.valueEdit.setHint(R.string.tag_multi_value_hint); // overwrite the above
            }
        }
    }

    /**
     * Create a mapping from tag keys to preset item and return those that coudn't be assigned
     * 
     * Tags that are in linked presets are assigned to that preset
     * 
     * @param preset PresetItem that we want to assign tags to
     * @param tags the tags we want to assign
     * @return map of tags that couldn't be assigned
     */
    private Map<String, String> addPresetsToTags(@Nullable PresetItem preset, @NonNull LinkedHashMap<String, String> tags) {
        LinkedHashMap<String, String> leftOvers = new LinkedHashMap<>();
        if (preset != null) {
            List<PresetItem> linkedPresetList = preset.getLinkedPresets(true);
            for (Entry<String, String> entry : tags.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (preset.hasKeyValue(key, value)) {
                    storePreset(key, preset);
                } else {
                    boolean found = false;
                    if (linkedPresetList != null) {
                        for (PresetItem linkedPreset : linkedPresetList) {
                            if (linkedPreset.hasKeyValue(key, value)) {
                                storePreset(key, linkedPreset);
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        leftOvers.put(key, value);
                    }
                }
            }
        } else {
            Log.e(DEBUG_TAG, "addPresetsToTags called with null preset");
        }
        return leftOvers;
    }

    private PresetItem getPreset(String key) {
        return tags2Preset.get(key);
    }

    private void storePreset(String key, PresetItem preset) {
        tags2Preset.put(key, preset);
    }

    private void clearPresets() {
        tags2Preset.clear();
    }

    @Override
    public Map<String, PresetItem> getAllPresets() {
        return tags2Preset;
    }

    private void addSecondaryPreset(PresetItem nonAssignedPreset) {
        secondaryPresets.add(nonAssignedPreset);
    }

    private void clearSecondaryPresets() {
        secondaryPresets.clear();
    }

    @Override
    public List<PresetItem> getSecondaryPresets() {
        return secondaryPresets;
    }

    @Override
    public PresetItem getBestPreset() {
        return primaryPresetItem;
    }

    @Override
    public void predictAddressTags(boolean allowBlanks) {
        loadEdits(Address.predictAddressTags(getActivity(), getType(), getOsmId(),
                ((StreetTagValueAdapter) nameAdapters.getStreetNameAdapter(null)).getElementSearch(), getKeyValueMap(allowBlanks), Address.DEFAULT_HYSTERESIS));
        updateAutocompletePresetItem(null);
    }

    private ArrayAdapter<String> getKeyAutocompleteAdapter(PresetItem preset, LinearLayout rowLayout, AutoCompleteTextView keyEdit) {
        // Use a set to prevent duplicate keys appearing
        Set<String> keys = new HashSet<>();

        if (preset == null && ((PropertyEditor) getActivity()).presets != null) {
            updateAutocompletePresetItem(rowLayout, null, false);
        }

        if (preset != null) {
            keys.addAll(preset.getFixedTags().keySet());
            keys.addAll(preset.getRecommendedTags().keySet());
            keys.addAll(preset.getOptionalTags().keySet());
        }

        if (((PropertyEditor) getActivity()).presets != null && elements[0] != null) { // FIXME multiselect
            keys.addAll(Preset.getAutocompleteKeys(((PropertyEditor) getActivity()).presets, elements[0].getType())); // FIXME
                                                                                                                      // multiselect
        }

        keys.removeAll(getUsedKeys(rowLayout, keyEdit));

        List<String> result = new ArrayList<>(keys);
        Collections.sort(result);
        return new ArrayAdapter<>(getActivity(), R.layout.autocomplete_row, result);
    }

    /**
     * Return true if the edited object has an address or is a "highway"
     * 
     * @param key
     * @param usedKeys
     * @return
     */
    public static boolean isStreetName(String key, Set<String> usedKeys) {
        return (Tags.KEY_ADDR_STREET.equalsIgnoreCase(key) || (Tags.KEY_NAME.equalsIgnoreCase(key) && usedKeys.contains(Tags.KEY_HIGHWAY)));
    }

    /**
     * Return true if the edited object has an address or is a "place"
     * 
     * @param key
     * @param usedKeys
     * @return
     */
    public static boolean isPlaceName(String key, Set<String> usedKeys) {
        return (Tags.KEY_ADDR_PLACE.equalsIgnoreCase(key) || (Tags.KEY_NAME.equalsIgnoreCase(key) && usedKeys.contains(Tags.KEY_PLACE)));
    }

    /**
     * Return true if the edited object could have a name in the name index
     * 
     * @param usedKeys
     * @return
     */
    public static boolean useNameSuggestions(Set<String> usedKeys) {
        return !(usedKeys.contains(Tags.KEY_HIGHWAY) || usedKeys.contains(Tags.KEY_WATERWAY) || usedKeys.contains(Tags.KEY_LANDUSE)
                || usedKeys.contains(Tags.KEY_NATURAL) || usedKeys.contains(Tags.KEY_RAILWAY));
    }

    private ArrayAdapter<?> getValueAutocompleteAdapter(PresetItem preset, LinearLayout rowLayout, TagEditRow row) {
        ArrayAdapter<?> adapter = null;
        String key = row.getKey();
        if (key != null && key.length() > 0) {
            HashSet<String> usedKeys = (HashSet<String>) getUsedKeys(rowLayout, null);

            boolean hasTagValues = row.tagValues != null && row.tagValues.size() > 1;
            if (isStreetName(key, usedKeys)) {
                adapter = nameAdapters.getStreetNameAdapter(hasTagValues ? row.tagValues : null);
            } else if (isPlaceName(key, usedKeys)) {
                adapter = nameAdapters.getPlaceNameAdapter(hasTagValues ? row.tagValues : null);
            } else if (!hasTagValues && key.equals(Tags.KEY_NAME) && (names != null) && useNameSuggestions(usedKeys)) {
                Log.d(DEBUG_TAG, "generate suggestions for name from name suggestion index");
                List<NameAndTags> values = (ArrayList<NameAndTags>) names.getNames(new TreeMap<>(getKeyValueMapSingle(rowLayout, true))); // FIXME
                if (values != null && !values.isEmpty()) {
                    Collections.sort(values);
                    adapter = new ArrayAdapter<>(getActivity(), R.layout.autocomplete_row, values);
                }
            } else {
                Map<String, Integer> counter = new HashMap<>();
                ArrayAdapter<ValueWithCount> adapter2 = new ArrayAdapter<>(getActivity(), R.layout.autocomplete_row);
                if (hasTagValues) {
                    for (String t : row.tagValues) {
                        if (t.equals("")) {
                            continue;
                        }
                        if (counter.containsKey(t)) {
                            counter.put(t, counter.get(t) + 1);
                        } else {
                            counter.put(t, 1);
                        }
                    }
                    List<String> keys = new ArrayList<>(counter.keySet());
                    Collections.sort(keys);
                    for (String t : keys) {
                        // FIXME determine description in some way
                        ValueWithCount v = new ValueWithCount(t, counter.get(t));
                        adapter2.add(v);
                    }
                }
                if (preset != null) { // note this will use the last applied preset which may be wrong FIXME
                    Collection<StringWithDescription> values = preset.getAutocompleteValues(key);
                    Log.d(DEBUG_TAG, "setting autocomplete adapter for values " + values + " based on " + preset.getName());
                    if (values != null && !values.isEmpty()) {
                        List<StringWithDescription> result = new ArrayList<>(values);
                        if (preset.sortIt(key)) {
                            Collections.sort(result);
                        }
                        for (StringWithDescription s : result) {
                            if (counter != null && counter.containsKey(s.getValue())) {
                                continue; // skip stuff that is already listed
                            }
                            adapter2.add(new ValueWithCount(s.getValue(), s.getDescription()));
                        }
                        Log.d(DEBUG_TAG, "key " + key + " type " + preset.getKeyType(key));
                    } else if (preset.isFixedTag(key)) {
                        for (StringWithDescription s : Preset.getAutocompleteValues(((PropertyEditor) getActivity()).presets, elements[0].getType(), key)) {
                            adapter2.add(new ValueWithCount(s.getValue(), s.getDescription()));
                        }
                    }
                } else if (((PropertyEditor) getActivity()).presets != null && elements[0] != null) {
                    Log.d(DEBUG_TAG, "generate suggestions for >" + key + "< from presets"); // only do this if there is
                                                                                             // no other source of
                                                                                             // suggestions
                    for (StringWithDescription s : Preset.getAutocompleteValues(((PropertyEditor) getActivity()).presets, elements[0].getType(), key)) {
                        adapter2.add(new ValueWithCount(s.getValue(), s.getDescription()));
                    }
                } else if (adapter2.getCount() == 0) {
                    // FIXME shouldn't happen but seems to
                    Log.d(DEBUG_TAG, "no suggestions for values for >" + key + "<");
                }
                if (adapter2.getCount() > 0) {
                    return adapter2;
                }
            }
        }
        return adapter;
    }

    /**
     * Insert a new row with one key and one value to edit.
     * 
     * @param aTagKey the key-value to start with
     * @param aTagValue the value to start with.
     * @param position the position where this should be inserted. set to -1 to insert at end, or 0 to insert at
     *            beginning.
     * @returns The new TagEditRow.
     */
    private TagEditRow insertNewEdit(final LinearLayout rowLayout, final String aTagKey, final ArrayList<String> tagValues, final int position) {
        final TagEditRow row = (TagEditRow) inflater.inflate(R.layout.tag_edit_row, rowLayout, false);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) { // stop Hint from wrapping
            row.valueEdit.setEllipsize(TruncateAt.END);
        }

        boolean same = true;
        if (tagValues.size() > 1) {
            for (int i = 1; i < tagValues.size(); i++) {
                if (!tagValues.get(i - 1).equals(tagValues.get(i))) {
                    same = false;
                    break;
                }
            }
        }
        row.setValues(aTagKey, tagValues, same);

        // If the user selects addr:street from the menu, auto-fill a suggestion
        row.keyEdit.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (Tags.KEY_ADDR_STREET.equals(parent.getItemAtPosition(position)) && row.getValue().length() == 0) {
                    ArrayAdapter<ValueWithCount> adapter = nameAdapters.getStreetNameAdapter(tagValues);
                    if (adapter != null && adapter.getCount() > 0) {
                        row.valueEdit.setText(adapter.getItem(0).getValue());
                    }
                } else if (Tags.KEY_ADDR_PLACE.equals(parent.getItemAtPosition(position)) && row.getValue().length() == 0) {
                    ArrayAdapter<ValueWithCount> adapter = nameAdapters.getPlaceNameAdapter(tagValues);
                    if (adapter != null && adapter.getCount() > 0) {
                        row.valueEdit.setText(adapter.getItem(0).getValue());
                    }
                } else {
                    if (primaryPresetItem != null) {
                        String hint = primaryPresetItem.getHint(parent.getItemAtPosition(position).toString());
                        if (hint != null) { //
                            row.valueEdit.setHint(hint);
                        } else if (!primaryPresetItem.getRecommendedTags().isEmpty() || !primaryPresetItem.getOptionalTags().isEmpty()) {
                            row.valueEdit.setHint(R.string.tag_value_hint);
                        }
                        if (row.getValue().length() == 0) {
                            String defaultValue = primaryPresetItem.getDefault(parent.getItemAtPosition(position).toString());
                            if (defaultValue != null) { //
                                row.valueEdit.setText(defaultValue);
                            }
                        }
                    }
                    // set focus on value
                    row.valueEdit.requestFocus();
                }
            }
        });
        row.keyEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            String originalKey;

            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                Log.d(DEBUG_TAG, "onFocusChange key");
                PresetItem preset = getPreset(aTagKey);
                if (hasFocus) {
                    // Log.d(DEBUG_TAG,"got focus");
                    originalKey = row.getKey();
                    row.keyEdit.setAdapter(getKeyAutocompleteAdapter(preset, rowLayout, row.keyEdit));
                    if (PropertyEditor.running && row.getKey().length() == 0)
                        row.keyEdit.showDropDown();
                } else {
                    String newKey = row.getKey();
                    if (!newKey.equals(originalKey)) { // our preset may have changed re-calc
                        originalKey = newKey;
                        updateAutocompletePresetItem(rowLayout, null, true);
                    }
                }
            }
        });
        row.valueEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            String originalValue;

            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                Log.d(DEBUG_TAG, "onFocusChange value");
                if (hasFocus) {
                    originalValue = row.getValue();
                    String key = row.getKey();
                    PresetItem preset = getPreset(key);
                    row.valueEdit.setAdapter(getValueAutocompleteAdapter(preset, rowLayout, row));
                    if (preset != null && preset.getKeyType(key) == PresetKeyType.MULTISELECT) {
                        // FIXME this should be somewhere better obvious since it creates a non obvious side effect
                        row.valueEdit.setTokenizer(new CustomAutoCompleteTextView.SingleCharTokenizer(preset.getDelimiter(key)));
                    }
                    if (Tags.isWebsiteKey(key) || (preset != null && ValueType.WEBSITE == preset.getValueType(key))) {
                        initWebsite(row.valueEdit);
                    } else if (Tags.isSpeedKey(key)) {
                        initMPHSpeed(getActivity(), row.valueEdit, ((PropertyEditor) getActivity()).getElement());
                    }
                    if (PropertyEditor.running) {
                        if (row.valueEdit.getText().length() == 0)
                            row.valueEdit.showDropDown();
                        // try { // hack to display numeric keyboard for numeric tag values
                        // // unluckily there is then no way to get an alpha-numeric keyboard
                        // int number = Integer.parseInt(valueEdit.getText().toString());
                        // valueEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
                        // } catch (NumberFormatException nfe) {
                        // // do nothing
                        // }
                    }
                } else {
                    // our preset may have changed re-calc
                    String newValue = row.getValue();
                    if (!newValue.equals(originalValue)) {
                        originalValue = newValue;
                        // Log.d(DEBUG_TAG,"lost focus");
                        // potentially we should update tagValues here
                        updateAutocompletePresetItem(rowLayout, null, true);
                    }
                }
            }
        });

        /**
         * This TextWatcher reacts to previously empty cells being filled to add additional rows where needed add
         * removes any formatting and truncates to maximum supported API string length
         */
        TextWatcher textWatcher = new TextWatcher() {
            private boolean wasEmpty;
            private String  prevValue = null;

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // nop
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                wasEmpty = row.isEmpty();
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (wasEmpty == (s.length() > 0)) {
                    // changed from empty to not-empty or vice versa
                    row.enableCheckBox();
                    ensureEmptyRow(rowLayout);
                }

                Util.sanitizeString(getActivity(), s, maxStringLength);

                // update presets but only if value has changed
                String newValue = s.toString();
                if (prevValue == null || !prevValue.equals(newValue)) {
                    prevValue = newValue;
                    updateAutocompletePresetItem(rowLayout, null, true);
                }
            }
        };
        row.keyEdit.addTextChangedListener(textWatcher);
        row.valueEdit.addTextChangedListener(textWatcher);

        row.valueEdit.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.d("TagEdit", "onItemClicked value");
                Object o = parent.getItemAtPosition(position);
                if (o instanceof Names.NameAndTags) {
                    row.valueEdit.setOrReplaceText(((NameAndTags) o).getName());
                    applyTagSuggestions(((NameAndTags) o).getTags());
                } else if (o instanceof ValueWithCount) {
                    row.valueEdit.setOrReplaceText(((ValueWithCount) o).getValue());
                } else if (o instanceof StringWithDescription) {
                    row.valueEdit.setOrReplaceText(((StringWithDescription) o).getValue());
                } else if (o instanceof String) {
                    row.valueEdit.setOrReplaceText((String) o);
                }
            }
        });

        row.selected.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!row.isEmpty()) {
                    if (isChecked) {
                        tagSelected();
                    } else {
                        deselectRow();
                    }
                }
                if (row.isEmpty()) {
                    row.deselect();
                }
            }
        });

        if (row.isEmpty()) {
            row.disableCheckBox();
        }
        rowLayout.addView(row, (position == -1) ? rowLayout.getChildCount() : position);
        //

        return row;
    }

    /**
     * A row representing an editable tag, consisting of edits for key and value, labels and a delete button. Needs to
     * be static, otherwise the inflater will not find it.
     * 
     * @author Jan
     */
    public static class TagEditRow extends LinearLayout implements SelectedRowsActionModeCallback.Row {

        private PropertyEditor             owner;
        private AutoCompleteTextView       keyEdit;
        private CustomAutoCompleteTextView valueEdit;
        private CheckBox                   selected;
        private ArrayList<String>          tagValues;
        private boolean                    same = true;

        public TagEditRow(Context context) {
            super(context);
            owner = (PropertyEditor) (isInEditMode() ? null : context); // Can only be instantiated inside TagEditor or
                                                                        // in Eclipse
        }

        public TagEditRow(Context context, AttributeSet attrs) {
            super(context, attrs);
            owner = (PropertyEditor) (isInEditMode() ? null : context); // Can only be instantiated inside TagEditor or
                                                                        // in Eclipse
        }

        // public TagEditRow(Context context, AttributeSet attrs, int defStyle) {
        // super(context, attrs, defStyle);
        // owner = (TagEditor) (isInEditMode() ? null : context); // Can only be instantiated inside TagEditor or in
        // Eclipse
        // }

        @Override
        protected void onFinishInflate() {
            super.onFinishInflate();
            if (isInEditMode())
                return; // allow visual editor to work

            keyEdit = (AutoCompleteTextView) findViewById(R.id.editKey);
            keyEdit.setOnKeyListener(owner.myKeyListener);
            // lastEditKey.setSingleLine(true);

            valueEdit = (CustomAutoCompleteTextView) findViewById(R.id.editValue);
            valueEdit.setOnKeyListener(owner.myKeyListener);

            selected = (CheckBox) findViewById(R.id.tagSelected);

            OnClickListener autocompleteOnClick = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (v.hasFocus()) {
                        ((AutoCompleteTextView) v).showDropDown();
                    }
                }
            };
            // set an empty adapter on both views to be on the safe side
            ArrayAdapter<String> empty = new ArrayAdapter<>(owner, R.layout.autocomplete_row, new String[0]);
            keyEdit.setAdapter(empty);
            valueEdit.setAdapter(empty);
            keyEdit.setOnClickListener(autocompleteOnClick);
            valueEdit.setOnClickListener(autocompleteOnClick);
        }

        @Override
        public void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Log.d(DEBUG_TAG, "onSizeChanged");

            if (w == 0 && h == 0) {
                return;
            }
            // Log.d(DEBUG_TAG,"w=" + w +" h="+h);
            // this is not really satisfactory
            keyEdit.setDropDownAnchor(valueEdit.getId());
            // keyEdit.setDropDownVerticalOffset(-h);
            // valueEdit.setDropDownVerticalOffset(-h);
            valueEdit.setParentWidth(w);
            //
        }

        /**
         * Sets key and value values
         * 
         * @param aTagKey the key value to set
         * @param aTagValue the value value to set
         * @return the TagEditRow object for convenience
         */
        public TagEditRow setValues(String aTagKey, ArrayList<String> tagValues, boolean same) {
            Log.d(DEBUG_TAG, "key " + aTagKey + " value " + tagValues);
            keyEdit.setText(aTagKey);
            this.tagValues = tagValues;
            this.same = same;
            if (same) {
                if (tagValues != null && !tagValues.isEmpty()) {
                    valueEdit.setText(tagValues.get(0));
                } else {
                    valueEdit.setText("");
                }
            } else {
                valueEdit.setHint(R.string.tag_multi_value_hint);
            }
            return this;
        }

        public String getKey() {
            return keyEdit.getText().toString();
        }

        public String getValue() { // FIXME check if returning the textedit value is actually ok
            return valueEdit.getText().toString();
        }

        /**
         * Deletes this row
         */
        @Override
        public void delete() { // FIXME the references to owner.tagEditorFragemnt are likely suspect
            deleteRow((LinearLayout) owner.tagEditorFragment.getOurView());
        }

        /**
         * Deletes this row
         */
        public void deleteRow(LinearLayout rowLayout) { // FIXME the references to owner.tagEditorFragemnt are likely
                                                        // suspect
            View cf = owner.getCurrentFocus();
            if (cf == keyEdit || cf == valueEdit) {
                // about to delete the row that has focus!
                // try to move the focus to the next row or failing that to the previous row
                int current = owner.tagEditorFragment.rowIndex(this);
                if (!owner.tagEditorFragment.focusRow(current + 1))
                    owner.tagEditorFragment.focusRow(current - 1);
            }
            rowLayout.removeView(this);
            if (isEmpty() && owner.tagEditorFragment != null) {
                owner.tagEditorFragment.ensureEmptyRow(rowLayout);
            }
        }

        /**
         * Checks if the fields in this row are empty
         * 
         * @return true if both fields are empty, false if at least one is filled
         */
        public boolean isEmpty() {
            return keyEdit.getText().toString().trim().equals("") && valueEdit.getText().toString().trim().equals("");
        }

        // return the status of the checkbox
        @Override
        public boolean isSelected() {
            return selected.isChecked();
        }

        @Override
        public void deselect() {
            selected.setChecked(false);
        }

        public void disableCheckBox() {
            selected.setEnabled(false);
        }

        void enableCheckBox() {
            selected.setEnabled(true);
        }
    }

    /**
     * Appy tags from name suggestion list, ask if overwriting
     * 
     * @param tags
     */
    public void applyTagSuggestions(Names.TagMap tags) {
        final LinkedHashMap<String, ArrayList<String>> currentValues = getKeyValueMap(true);

        boolean replacedValue = false;

        // Fixed tags, always have a value. We overwrite mercilessly.
        for (Entry<String, String> tag : tags.entrySet()) {
            ArrayList<String> oldValue = currentValues.put(tag.getKey(), Util.getArrayList(tag.getValue()));
            if (oldValue != null && !oldValue.isEmpty() && !oldValue.contains(tag.getValue()))
                replacedValue = true;
        }
        if (replacedValue) {
            Builder dialog = new AlertDialog.Builder(getActivity());
            dialog.setTitle(R.string.tag_editor_name_suggestion);
            dialog.setMessage(R.string.tag_editor_name_suggestion_overwrite_message);
            dialog.setPositiveButton(R.string.replace, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    loadEdits(currentValues);// FIXME
                }
            });
            dialog.setNegativeButton(R.string.cancel, null);
            dialog.create().show();
        } else
            loadEdits(currentValues);// FIXME

        // TODO while applying presets automatically seems like a good idea, it needs some further thought
        if (prefs.enableAutoPreset()) {
            PresetItem p = Preset.findBestMatch(((PropertyEditor) getActivity()).presets, getKeyValueMapSingle(false)); // FIXME
            if (p != null) {
                applyPreset(p, false, false);
            }
        }
    }

    private void tagSelected() {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        synchronized (actionModeCallbackLock) {
            if (tagSelectedActionModeCallback == null) {
                tagSelectedActionModeCallback = new TagSelectedActionModeCallback(this, rowLayout);
                ((AppCompatActivity) getActivity()).startSupportActionMode(tagSelectedActionModeCallback);
            }
        }
    }

    @Override
    public void deselectRow() {
        synchronized (actionModeCallbackLock) {
            if (tagSelectedActionModeCallback != null) {
                if (tagSelectedActionModeCallback.rowsDeselected(false)) {
                    tagSelectedActionModeCallback = null;
                }
            }
        }
    }

    @Override
    public void selectAllRows() { // select all tags
        LinearLayout rowLayout = (LinearLayout) getOurView();
        if (loaded) {
            int i = rowLayout.getChildCount();
            while (--i >= 0) {
                TagEditRow row = (TagEditRow) rowLayout.getChildAt(i);
                if (row.selected.isEnabled()) {
                    row.selected.setChecked(true);
                }
            }
        }
    }

    @Override
    public void deselectAllRows() { // deselect all tags
        LinearLayout rowLayout = (LinearLayout) getOurView();
        if (loaded) {
            int i = rowLayout.getChildCount();
            while (--i >= 0) {
                TagEditRow row = (TagEditRow) rowLayout.getChildAt(i);
                if (row.selected.isEnabled()) {
                    row.selected.setChecked(false);
                }
            }
        }
    }

    /**
     * Ensures that at least one empty row exists (creating one if needed)
     * 
     * @param rowLayout layout holding the rows
     * @return the first empty row found (or the one created), or null if loading was not finished (loaded == false),
     *         null if rowLayout is null
     */
    @Nullable
    private TagEditRow ensureEmptyRow(@Nullable LinearLayout rowLayout) {
        if (rowLayout == null) {
            return null;
        }
        TagEditRow ret = null;
        if (loaded) {
            int i = rowLayout.getChildCount();
            while (--i >= 0) {
                TagEditRow row = (TagEditRow) rowLayout.getChildAt(i);
                boolean isEmpty = row.isEmpty();
                if (ret == null) {
                    ret = isEmpty ? row : insertNewEdit(rowLayout, "", new ArrayList<String>(), -1);
                } else if (isEmpty) {
                    row.deleteRow(rowLayout);
                }
            }
            if (ret == null) {
                ret = insertNewEdit(rowLayout, "", new ArrayList<String>(), -1);
            }
        }
        return ret;
    }

    /**
     * Focus on the value field of the first tag with non empty key and empty value
     * 
     * @param key key that we want to find the value field for
     * @return true if successful
     */
    boolean focusOnEmptyValue() {
        Log.d(DEBUG_TAG, "focusOnEmptyValue");
        LinearLayout rowLayout = (LinearLayout) getOurView();
        return focusOnEmptyValue(rowLayout);
    }

    /**
     * Focus on the first empty value field
     * 
     * @param rowLayout layout holding the ros
     * @return true if successful
     */
    private boolean focusOnEmptyValue(LinearLayout rowLayout) {
        boolean found = false;
        for (int i = 0; i < rowLayout.getChildCount(); i++) {
            TagEditRow ter = (TagEditRow) rowLayout.getChildAt(i);
            if (ter.getKey() != null && !ter.getKey().equals("") && ter.getValue().equals("")) {
                focusRowValue(rowLayout, rowIndex(rowLayout, ter));
                found = true;
                break;
            }
        }
        return found;
    }

    /**
     * Move the focus to the key field of the specified row.
     * 
     * @param index The index of the row to move to, counting from 0.
     * @return true if the row was successfully focused, false otherwise.
     */
    private boolean focusRow(int index) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        TagEditRow row = (TagEditRow) rowLayout.getChildAt(index);
        return row != null && row.keyEdit.requestFocus();
    }

    /**
     * Move the focus to the value field of the specified row.
     * 
     * @param index The index of the row to move to, counting from 0.
     * @return true if the row was successfully focused, false otherwise.
     */
    private boolean focusRowValue(LinearLayout rowLayout, int index) {
        TagEditRow row = (TagEditRow) rowLayout.getChildAt(index);
        return row != null && row.valueEdit.requestFocus();
    }

    /**
     * Given a tag edit row, calculate its position.
     *
     * @param row The tag edit row to find.
     * @return The position counting from 0 of the given row, or -1 if it couldn't be found.
     */
    private int rowIndex(TagEditRow row) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        return rowIndex(rowLayout, row);
    }

    /**
     * Get the index of a specific row in the layout
     * 
     * @param rowLayout layout holding the rows
     * @param row row we want the index for
     * @return the index or -1 if not found
     */
    private int rowIndex(LinearLayout rowLayout, TagEditRow row) {
        for (int i = rowLayout.getChildCount() - 1; i >= 0; --i) {
            if (rowLayout.getChildAt(i) == row)
                return i;
        }
        return -1;
    }

    /**
     * Focus on the value field of a tag with key "key"
     * 
     * @param key key that we want to find the value field for
     * @return true if successful
     */
    private boolean focusOnValue(LinearLayout rowLayout, String key) {
        boolean found = false;
        for (int i = rowLayout.getChildCount() - 1; i >= 0; --i) {
            TagEditRow ter = (TagEditRow) rowLayout.getChildAt(i);
            if (ter.getKey().equals(key)) {
                focusRowValue(rowLayout, rowIndex(rowLayout, ter));
                found = true;
                break;
            }
        }
        return found;
    }

    /**
     * Applies a preset (e.g. selected from the dialog or MRU), i.e. adds the tags from the preset to the current tag
     * set
     * 
     * @param item the preset to apply
     */
    void applyPreset(PresetItem item) {
        applyPreset(item, false, true);
    }

    /**
     * Applies a preset (e.g. selected from the dialog or MRU), i.e. adds the tags from the preset to the current tag
     * set
     * 
     * @param item the preset item to apply
     * @param addOptional add optional tags if true
     * @param addToMRU add to preset MRU list if true
     */
    void applyPreset(PresetItem item, boolean addOptional, boolean addToMRU) {
        LinkedHashMap<String, ArrayList<String>> currentValues = getKeyValueMap(true);
        boolean wasEmpty = currentValues.size() == 0;
        boolean replacedValue = false;

        Log.d(DEBUG_TAG, "applying preset " + item.getName());

        // remove everything that doesn't have a value
        // given that these are likely leftovers from a previous preset
        Set<String> keySet = new HashSet<>(currentValues.keySet()); // shallow copy
        for (String key : keySet) {
            ArrayList<String> list = currentValues.get(key);
            if (list == null || list.isEmpty()) {
                currentValues.remove(key);
            }
        }

        // Fixed tags, always have a value. We overwrite mercilessly.
        for (Entry<String, StringWithDescription> tag : item.getFixedTags().entrySet()) {
            String v = tag.getValue().getValue();
            ArrayList<String> oldValue = currentValues.put(tag.getKey(), Util.getArrayList(v));
            if (oldValue != null && !oldValue.isEmpty() && !oldValue.contains(v) && !(oldValue.size() == 1 && "".equals(oldValue.get(0)))) {
                replacedValue = true;
            }
        }

        // Recommended tags, no fixed value is given. We add only those that do not already exist.
        for (Entry<String, StringWithDescription[]> tag : item.getRecommendedTags().entrySet()) {
            addTagFromPreset(item, currentValues, tag.getKey());
        }

        // Optional tags, no fixed value is given. We add only those that do not already exist.
        if (addOptional) {
            for (Entry<String, StringWithDescription[]> tag : item.getOptionalTags().entrySet()) {
                addTagFromPreset(item, currentValues, tag.getKey());
            }
        }

        loadEdits(currentValues);
        if (replacedValue) {
            Snack.barWarning(getActivity(), R.string.toast_preset_overwrote_tags);
        }

        if (wasEmpty || getBestPreset() == null) {
            // preset is what we just applied
            updateAutocompletePresetItem(item, addToMRU);
        } else {
            // re-determine best preset
            updateAutocompletePresetItem(null, addToMRU);
        }

        // only focus on an empty field if we are actually being shown
        if (propertyEditorListener != null && propertyEditorListener.onTop(this)) {
            focusOnEmptyValue();
        }
    }

    /**
     * Add tag from preset if the tag doesn't exist, execute JS if present
     * 
     * If evaluating the JS returns null, the key is removed
     * 
     * @param item current Preset
     * @param tags map of current tags
     * @param key the key we are processing
     */
    private void addTagFromPreset(PresetItem item, Map<String, ArrayList<String>> tags, String key) {
        String value = item.getDefault(key) == null ? "" : item.getDefault(key);
        if (!tags.containsKey(key)) {
            String script = item.getJavaScript(key);
            if (script != null) {
                try {
                    value = de.blau.android.javascript.Utils.evalString(getActivity(), " " + key, script, buildEdits(), tags, value);
                    if (value == null) {
                        tags.remove(key);
                        return;
                    }
                } catch (Exception ex) {
                    Snack.barError(getActivity(), ex.getLocalizedMessage());
                }
            }
            tags.put(key, Util.getArrayList(value));
        }
    }

    /**
     * Update the MRU preset view
     */
    void recreateRecentPresetView() {
        Log.d(DEBUG_TAG, "Updating MRU prests");
        FragmentManager fm = getChildFragmentManager();
        Fragment recentPresetsFragment = fm.findFragmentByTag(PropertyEditor.RECENTPRESETS_FRAGMENT);
        if (recentPresetsFragment != null) {
            ((RecentPresetsFragment) recentPresetsFragment).recreateRecentPresetView();
        }
    }

    /**
     * Merge a set of tags in to the current ones
     * 
     * @param newTags
     */
    private void mergeTags(Map<String, String> newTags) {
        LinkedHashMap<String, ArrayList<String>> currentValues = getKeyValueMap(true);

        boolean replacedValue = false;

        // Fixed tags, always have a value. We overwrite mercilessly.
        for (Entry<String, String> tag : newTags.entrySet()) {
            ArrayList<String> oldValue = currentValues.put(tag.getKey(), Util.getArrayList(tag.getValue()));
            if (oldValue != null && !oldValue.isEmpty() && !oldValue.contains(tag.getValue())) {
                replacedValue = true;
            }
        }

        loadEdits(currentValues);
        if (replacedValue) {
            Snack.barWarning(getActivity(), R.string.toast_preset_overwrote_tags);
        }
        focusOnEmptyValue();
    }

    /**
     * Merge a set of tags in to the current ones, with potentially empty keys
     * 
     * @param newTags
     * @param replace
     */
    private void mergeTags(ArrayList<KeyValue> newTags, boolean replace) {
        LinkedHashMap<String, ArrayList<String>> currentValues = getKeyValueMap(true);
        HashMap<String, KeyValue> keyIndex = new HashMap<>(); // needed for de-duping

        List<KeyValue> keysAndValues = new ArrayList<>();
        for (Entry<String, ArrayList<String>> entry : currentValues.entrySet()) {
            String key = entry.getKey();
            KeyValue keyValue = new KeyValue(key, entry.getValue());
            keysAndValues.add(keyValue);
            keyIndex.put(key, keyValue);
        }

        boolean replacedValue = false;

        //
        for (KeyValue tag : newTags) {
            KeyValue keyValue = keyIndex.get(tag.getKey());
            if (keyValue != null && replace) { // exists
                keyValue.setValue(tag.getValue());
                replacedValue = true;
            } else {
                keysAndValues.add(new KeyValue(tag.getKey(), tag.getValue()));
            }
        }

        // this code needs to be duplicated because we can't use a map here
        LinearLayout rowLayout = (LinearLayout) getOurView();
        loaded = false;
        rowLayout.removeAllViews();
        for (KeyValue keyValue : keysAndValues) {
            insertNewEdit(rowLayout, keyValue.getKey(), keyValue.getValues(), -1);
        }
        loaded = true;
        ensureEmptyRow(rowLayout);

        if (replacedValue) {
            Snack.barWarning(getActivity(), R.string.toast_preset_overwrote_tags);
        }
        focusOnEmptyValue();
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        // final MenuInflater inflater = getSupportMenuInflater();
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.tag_menu, menu);
        menu.findItem(R.id.tag_menu_mapfeatures).setEnabled(propertyEditorListener.isConnectedOrConnecting());
        menu.findItem(R.id.tag_menu_paste).setVisible(pasteIsPossible());
        menu.findItem(R.id.tag_menu_paste_from_clipboard).setVisible(pasteFromClipboardIsPossible());
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        // disable address tagging for stuff that won't have an address
        // menu.findItem(R.id.tag_menu_address).setVisible(!type.equals(Way.NAME) ||
        // element.hasTagKey(Tags.KEY_BUILDING));
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            ((PropertyEditor) getActivity()).sendResultAndFinish();
            return true;
        case R.id.tag_menu_address:
            predictAddressTags(false);
            return true;
        case R.id.tag_menu_sourcesurvey:
            doSourceSurvey();
            return true;
        case R.id.tag_menu_apply_preset:
            PresetItem pi = Preset.findBestMatch(((PropertyEditor) getActivity()).presets, getKeyValueMapSingle(false)); // FIXME
            if (pi != null) {
                applyPreset(pi, true, false);
            }
            return true;
        case R.id.tag_menu_paste:
            paste(true);
            return true;
        case R.id.tag_menu_paste_from_clipboard:
            pasteFromClipboard(true);
            return true;
        case R.id.tag_menu_revert:
            doRevert();
            return true;
        case R.id.tag_menu_mapfeatures:
            startActivity(Preset.getMapFeaturesIntent(getActivity(), getBestPreset()));
            return true;
        case R.id.tag_menu_resetMRU:
            for (Preset p : ((PropertyEditor) getActivity()).presets)
                p.resetRecentlyUsed();
            ((PropertyEditor) getActivity()).recreateRecentPresetView();
            return true;
        case R.id.tag_menu_reset_address_prediction:
            // simply overwrite with an empty file
            Address.resetLastAddresses(getActivity());
            return true;
        case R.id.tag_menu_js_console:
            de.blau.android.javascript.Utils.jsConsoleDialog(getActivity(), R.string.js_console_msg_debug, new EvalCallback() {
                @Override
                public String eval(String input) {
                    return de.blau.android.javascript.Utils.evalString(getActivity(), "JS Preset Test", input, buildEdits(), getKeyValueMap(true), "test");
                }
            });
            return true;
        case R.id.tag_menu_select_all:
            selectAllRows();
            return true;
        case R.id.tag_menu_help:
            HelpViewer.start(getActivity(), R.string.help_propertyeditor);
            return true;
        }
        return false;
    }

    /**
     * Collect all key-value pairs into a LinkedHashMap<String,String>
     * 
     * @param allowBlanks If true, includes key-value pairs where one or the other is blank.
     * @return The LinkedHashMap<String,String> of key-value pairs.
     */
    private LinkedHashMap<String, ArrayList<String>> getKeyValueMap(final boolean allowBlanks) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        return getKeyValueMap(rowLayout, allowBlanks);
    }

    private LinkedHashMap<String, ArrayList<String>> getKeyValueMap(LinearLayout rowLayout, final boolean allowBlanks) {

        final LinkedHashMap<String, ArrayList<String>> tags = new LinkedHashMap<>();

        if (rowLayout == null && savedTags != null) {
            return savedTags;
        }

        if (rowLayout != null) {
            processKeyValues(rowLayout, new KeyValueHandler() {
                @Override
                public void handleKeyValue(final EditText keyEdit, final EditText valueEdit, final ArrayList<String> tagValues) {
                    String key = keyEdit.getText().toString().trim();
                    String value = valueEdit.getText().toString().trim();
                    boolean keyBlank = "".equals(key);
                    boolean valueBlank = "".equals(value);
                    boolean bothBlank = keyBlank && valueBlank;
                    boolean neitherBlank = !keyBlank && !valueBlank;
                    if (!bothBlank) {
                        // both blank is never acceptable
                        if (neitherBlank || allowBlanks || (valueBlank && tagValues != null && !tagValues.isEmpty())) {
                            if (valueBlank) {
                                tags.put(key, tagValues.size() == 1 ? Util.getArrayList("") : tagValues);
                            } else {
                                tags.put(key, Util.getArrayList(value));
                            }
                        }
                    }
                }
            });
        } else {
            Log.e(DEBUG_TAG, "rowLayout null in getKeyValueMapSingle");
        }
        // for (String key:tags.keySet()) {
        // Log.d(DEBUG_TAG,"getKeyValueMap Key " + key + " " + tags.get(key));
        // }
        return tags;
    }

    /**
     * Version of above that ignores multiple values
     * 
     * @param allowBlanks
     * @return
     */
    public LinkedHashMap<String, String> getKeyValueMapSingle(final boolean allowBlanks) {
        LinearLayout rowLayout = (LinearLayout) getOurView();
        return getKeyValueMapSingle(rowLayout, allowBlanks);
    }

    private LinkedHashMap<String, String> getKeyValueMapSingle(LinearLayout rowLayout, final boolean allowBlanks) {

        final LinkedHashMap<String, String> tags = new LinkedHashMap<>();
        if (rowLayout == null && savedTags != null) {
            for (Entry<String, ArrayList<String>> entry : savedTags.entrySet()) {
                String key = entry.getKey().trim();
                ArrayList<String> tagValues = entry.getValue();
                String value = tagValues != null && !tagValues.isEmpty() ? (tagValues.get(0) != null ? tagValues.get(0) : "") : "";
                boolean valueBlank = "".equals(value);
                boolean bothBlank = "".equals(key) && valueBlank;
                boolean neitherBlank = !"".equals(key) && !valueBlank;
                if (!bothBlank) {
                    // both blank is never acceptable
                    if (neitherBlank || allowBlanks || valueBlank) {
                        if (valueBlank) {
                            tags.put(key, tagValues == null || tagValues.size() == 1 ? "" : tagValues.get(0)); // FIXME
                                                                                                               // if
                                                                                                               // multi-select
                        } else {
                            tags.put(key, value);
                        }
                    }
                }
            }
        }
        if (rowLayout != null) {
            processKeyValues(rowLayout, new KeyValueHandler() {
                @Override
                public void handleKeyValue(final EditText keyEdit, final EditText valueEdit, final ArrayList<String> tagValues) {
                    String key = keyEdit.getText().toString().trim();
                    String value = valueEdit.getText().toString().trim();
                    boolean valueBlank = "".equals(value);
                    boolean bothBlank = "".equals(key) && valueBlank;
                    boolean neitherBlank = !"".equals(key) && !valueBlank;
                    if (!bothBlank) {
                        // both blank is never acceptable
                        boolean hasValues = tagValues != null && !tagValues.isEmpty();
                        if (neitherBlank || allowBlanks || (valueBlank && hasValues)) {
                            if (valueBlank) {
                                tags.put(key, "");
                            } else {
                                tags.put(key, value);
                            }
                        }
                    }
                }
            });
        } else {
            Log.e(DEBUG_TAG, "rowLayout null in getKeyValueMapSingle");
        }
        return tags;
    }

    /**
     * Given an edit field of an OSM key value, determine it's corresponding source key. For example, the source of
     * "name" is "source:name". The source of "source" is "source". The source of "mf:name" is "mf.source:name".
     * 
     * @param keyEdit The edit field of the key to be sourced.
     * @return The source key for the given key.
     */
    private static String sourceForKey(final String key) {
        String result = "source";
        if (key != null && !key.equals("") && !key.equals("source")) {
            // key is neither blank nor "source"
            // check if it's namespaced
            int i = key.indexOf(':');
            if (i == -1) {
                result = "source:" + key;
            } else {
                // handle already namespaced keys as per
                // http://wiki.openstreetmap.org/wiki/Key:source
                result = key.substring(0, i) + ".source" + key.substring(i);
            }
        }
        return result;
    }

    private void doSourceSurvey() { // FIXME
        // determine the key (if any) that has the current focus in the key or its value
        final String[] focusedKey = new String[] { null }; // array to work around unsettable final
        processKeyValues(new KeyValueHandler() {
            @Override
            public void handleKeyValue(final EditText keyEdit, final EditText valueEdit, final ArrayList<String> tagValues) {
                if (keyEdit.isFocused() || valueEdit.isFocused()) {
                    focusedKey[0] = keyEdit.getText().toString().trim();
                }
            }
        });
        // ensure source(:key)=survey is tagged
        final String sourceKey = sourceForKey(focusedKey[0]);
        final boolean[] sourceSet = new boolean[] { false }; // array to work around unsettable final
        processKeyValues(new KeyValueHandler() {
            @Override
            public void handleKeyValue(final EditText keyEdit, final EditText valueEdit, final ArrayList<String> tagValues) {
                if (!sourceSet[0]) {
                    String key = keyEdit.getText().toString().trim();
                    String value = valueEdit.getText().toString().trim();
                    // if there's a blank row - use them
                    if (key.equals("") && value.equals("")) {
                        key = sourceKey;
                        keyEdit.setText(key);
                    }
                    if (key.equals(sourceKey)) {
                        valueEdit.setText(Tags.VALUE_SURVEY);
                        sourceSet[0] = true;
                    }
                }
            }
        });
        if (!sourceSet[0]) {
            // source wasn't set above - add a new pair
            ArrayList<String> v = new ArrayList<>();
            v.add(Tags.VALUE_SURVEY);
            insertNewEdit((LinearLayout) getOurView(), sourceKey, v, -1);
        }
    }

    @Override
    public boolean pasteIsPossible() {
        if (copiedTags == null) {
            copiedTags = savingHelper.load(getActivity(), PropertyEditor.COPIED_TAGS_FILE, false);
        }
        return copiedTags != null;
    }

    @Override
    public boolean paste(boolean replace) {
        if (copiedTags != null) {
            mergeTags(copiedTags);
        } else {
            copiedTags = savingHelper.load(getActivity(), PropertyEditor.COPIED_TAGS_FILE, false);
            if (copiedTags != null) {
                mergeTags(copiedTags);
            }
        }
        updateAutocompletePresetItem(null);
        return copiedTags != null;
    }

    @Override
    public boolean pasteFromClipboardIsPossible() {
        return ClipboardUtils.checkForText(getActivity());
    }

    @Override
    public boolean pasteFromClipboard(boolean replace) {
        ArrayList<KeyValue> paste = ClipboardUtils.getKeyValues(getActivity());
        if (paste != null) {
            mergeTags(paste, replace);
        }
        updateAutocompletePresetItem(null);
        return paste != null;
    }

    @Override
    public void copyTags(Map<String, String> tags) {
        copiedTags = new LinkedHashMap<>(tags);
        ClipboardUtils.copyTags(getActivity(), copiedTags);
    }

    /**
     * reload original arguments
     */
    void doRevert() {
        loadEdits(buildEdits());
        updateAutocompletePresetItem(null);
    }

    /**
     * @return the OSM ID of the element currently edited by the editor
     */
    public long getOsmId() { // FIXME
        return osmIds[0];
    }

    public String getType() {// FIXME
        return types[0];
    }

    /**
     * Get all key values currently in the editor, optionally skipping one field.
     * 
     * @param ignoreEdit optional - if not null, this key field will be skipped, i.e. the key in it will not be included
     *            in the output
     * @return the set of all (or all but one) keys currently entered in the edit boxes
     */
    private Set<String> getUsedKeys(LinearLayout rowLayout, final EditText ignoreEdit) {
        final HashSet<String> keys = new HashSet<>();
        processKeyValues(rowLayout, new KeyValueHandler() {
            @Override
            public void handleKeyValue(final EditText keyEdit, final EditText valueEdit, final ArrayList<String> tagValues) {
                if (!keyEdit.equals(ignoreEdit)) {
                    String key = keyEdit.getText().toString().trim();
                    if (key.length() > 0) {
                        keys.add(key);
                    }
                }
            }
        });
        return keys;
    }

    @Override
    public void deselectHeaderCheckBox() {
        CheckBox headerCheckBox = (CheckBox) getView().findViewById(R.id.header_tag_selected);
        headerCheckBox.setChecked(false);
    }

    /**
     * Return the view we have our rows in and work around some android craziness
     * 
     * @return the row container view
     */
    @NonNull
    private View getOurView() {
        // android.support.v4.app.NoSaveStateFrameLayout
        View v = getView();
        if (v != null) {
            if (v.getId() == R.id.edit_row_layout) {
                Log.d(DEBUG_TAG, "got correct view in getView");
                return v;
            } else {
                v = v.findViewById(R.id.edit_row_layout);
                if (v == null) {
                    Log.d(DEBUG_TAG, "didn't find R.id.edit_row_layout");
                    throw new UiStateException("didn't find R.id.edit_row_layout");
                } else {
                    Log.d(DEBUG_TAG, "Found R.id.edit_row_layout");
                }
                return v;
            }
        } else {
            // given that this is always fatal might as well throw the exception here
            Log.d(DEBUG_TAG, "got null view in getView");
            throw new UiStateException("got null view in getView");
        }
    }

    /**
     * Return tags copied or cut
     * 
     * @return map containing the copied tags
     */
    public LinkedHashMap<String, String> getCopiedTags() {
        return (LinkedHashMap<String, String>) copiedTags;
    }

    public void enableRecentPresets() {
        FragmentManager fm = getChildFragmentManager();
        Fragment recentPresetsFragment = fm.findFragmentByTag(PropertyEditor.RECENTPRESETS_FRAGMENT);
        if (recentPresetsFragment != null) {
            ((RecentPresetsFragment) recentPresetsFragment).enable();
        }
    }

    public void disableRecentPresets() {
        FragmentManager fm = getChildFragmentManager();
        Fragment recentPresetsFragment = fm.findFragmentByTag(PropertyEditor.RECENTPRESETS_FRAGMENT);
        if (recentPresetsFragment != null) {
            ((RecentPresetsFragment) recentPresetsFragment).disable();
        }
    }

    /**
     * Update the original list of tags to reflect edits
     * 
     * Note this will silently remove tags with empty key or value
     * 
     * @return list of maps containing the tags
     */
    public List<LinkedHashMap<String, String>> getUpdatedTags() {
        @SuppressWarnings("unchecked")

        ArrayList<Map<String, String>> oldTags = (ArrayList<Map<String, String>>) getArguments().getSerializable(TAGS_KEY);
        // make a (nearly) full copy
        ArrayList<LinkedHashMap<String, String>> newTags = new ArrayList<>();
        for (Map<String, String> map : oldTags) {
            newTags.add(new LinkedHashMap<>(map));
        }

        LinkedHashMap<String, ArrayList<String>> edits = getKeyValueMap(true);
        if (edits == null) {
            // if we didn't get a LinkedHashMap as input we need to copy
            ArrayList<LinkedHashMap<String, String>> newOldTags = new ArrayList<>();
            for (Map<String, String> map : oldTags) {
                newOldTags.add(new LinkedHashMap<>(map));
            }
            return newOldTags;
        }

        for (LinkedHashMap<String, String> map : newTags) {
            for (String key : new TreeSet<>(map.keySet())) {
                if (edits.containsKey(key)) {
                    List<String> valueList = edits.get(key);
                    if (valueList.size() == 1) {
                        String value = valueList.get(0).trim();
                        if (saveTag(key, value)) {
                            addTagToMap(map, key, value);
                        } else {
                            map.remove(key); // zap stuff with empty values or just the HTTP prefix
                        }
                    }
                } else { // key deleted
                    map.remove(key);
                }
            }
            // check for new tags
            for (Entry<String, ArrayList<String>> entry : edits.entrySet()) {
                String editsKey = entry.getKey();
                List<String> valueList = entry.getValue();
                if (editsKey != null && !"".equals(editsKey) && !map.containsKey(editsKey) && valueList.size() == 1) { // zap
                                                                                                                       // empty
                                                                                                                       // stuff
                                                                                                                       // or
                                                                                                                       // just
                                                                                                                       // the
                                                                                                                       // HTTP
                                                                                                                       // prefix
                                                                                                                       // FIXME
                                                                                                                       // throw
                                                                                                                       // an
                                                                                                                       // exception
                                                                                                                       // when
                                                                                                                       // we
                                                                                                                       // do
                                                                                                                       // zap
                    String value = valueList.get(0).trim();
                    if (saveTag(editsKey, value)) {
                        addTagToMap(map, editsKey, value);
                    }
                }
            }
        }
        return newTags;
    }

    /**
     * Check if we should keep this
     * 
     * @param key string containing the key
     * @param value string containing the value
     * @return true if value and key isn't empty and isn't the HTTP/HTTPS prefix
     */
    private boolean saveTag(String key, String value) {
        return !"".equals(value) && !onlyWebsitePrefix(key, value) && !onlyMphSuffix(key, value);
    }

    /**
     * Check if this is a website tag with open the protocol prefix it it
     * 
     * @param key key of the tag
     * @param value value of the tag
     * @return true if this is only http:// or https://
     */
    boolean onlyWebsitePrefix(String key, String value) {
        PresetItem pi = getPreset(key);
        return (Tags.isWebsiteKey(key) || (pi != null && ValueType.WEBSITE == pi.getValueType(key)))
                && (Tags.HTTP_PREFIX.equalsIgnoreCase(value) || Tags.HTTPS_PREFIX.equalsIgnoreCase(value));
    }

    /**
     * Check if this is a speed tag that only contains MPH
     * 
     * @param key key of the tag
     * @param value value of the tag
     * @return true if this is only MPH
     */
    boolean onlyMphSuffix(String key, String value) {
        return Tags.isSpeedKey(key) && Tags.MPH.trim().equalsIgnoreCase(value);
    }

    /**
     * Add tag if it doesn't exist
     * 
     * @param rowLayout layout holding the rows
     * @param key key of the tag
     * @param value value of the tag
     * @param replace if true replace existing key values, otherwise don't
     * @param update if true update the the best matched presets
     */
    private void addTag(LinearLayout rowLayout, String key, String value, boolean replace, boolean update) {
        Log.d(DEBUG_TAG, "adding tag " + key + "=" + value);
        LinkedHashMap<String, ArrayList<String>> currentValues = getKeyValueMap(rowLayout, true);
        if (!currentValues.containsKey(key) || replace) {
            currentValues.put(key, Util.getArrayList(value));
            loadEdits(rowLayout, currentValues);
            if (update) {
                updateAutocompletePresetItem(null);
            }
        }
    }

    @Override
    public void updateSingleValue(String key, String value) {
        LinkedHashMap<String, ArrayList<String>> currentValues = getKeyValueMap(true);
        currentValues.put(key, Util.getArrayList(value));
        loadEdits(currentValues);
        updateAutocompletePresetItem(null);
    }

    @Override
    public void updateTags(Map<String, String> tags, boolean flush) {
        loadEditsSingle(tags);
        updateAutocompletePresetItem(null);
    }

    @Override
    public void revertTags() {
        doRevert();
    }

    @Override
    public void deleteTag(final String key) {
        LinearLayout l = (LinearLayout) getOurView();
        if (l != null) {
            for (int i = l.getChildCount() - 1; i >= 0; --i) {
                TagEditRow ter = (TagEditRow) l.getChildAt(i);
                if (ter.getKey().equals(key)) {
                    ter.delete();
                    break;
                }
            }
        }
    }

    /**
     * Add key-value to a map stripping training list separator
     * 
     * @param map target map
     * @param key string containing the key
     * @param value string containing the value
     */
    private void addTagToMap(Map<String, String> map, String key, String value) {
        if (primaryPresetItem != null && primaryPresetItem.getKeyType(key) == PresetKeyType.MULTISELECT) {
            // trim potential trailing separators
            if (value.endsWith(String.valueOf(primaryPresetItem.getDelimiter(key)))) {
                value = value.substring(0, value.length() - 1);
            }
        }
        map.put(key, value);
    }

    /**
     * Add http:// to empty EditTexts that are supposed to contain a website and set input mode
     * 
     * @param valueEdit the EditTExt holding the value
     */
    public static void initWebsite(final EditText valueEdit) {
        valueEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (valueEdit.getText().length() == 0) {
            valueEdit.setText(Tags.HTTP_PREFIX);
            valueEdit.setSelection(Tags.HTTP_PREFIX.length());
        }
    }

    /**
     * Add mph to empty EditTexts that are supposed to contain speed and are in relevant country and set input mode
     * 
     * @param ctx Android Context
     * @param valueEdit the EditTExt holding the value
     * @param e the associated OsmElement
     */
    public static void initMPHSpeed(Context ctx, final EditText valueEdit, OsmElement e) {
        valueEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (valueEdit.getText().length() == 0 && App.getGeoContext(ctx).imperial(e)) { // in the case of multi-select
                                                                                       // there is no guarantee that
                                                                                       // this makes sense
            valueEdit.setText(Tags.MPH);
            valueEdit.setSelection(0);
        }
    }

    @Override
    public void applyPreset(PresetItem preset, boolean addOptional) {
        applyPreset(preset, addOptional, true);
    }
}
