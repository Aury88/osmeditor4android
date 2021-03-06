package de.blau.android.presets;

import java.util.List;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.support.test.filters.LargeTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import de.blau.android.Main;
import de.blau.android.osm.OsmElement.ElementType;
import de.blau.android.presets.Preset.PresetItem;
import de.blau.android.util.SearchIndexUtils;

/**
 * This is just a convenient way of generating the default preset dump
 * 
 * @author simon
 *
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SynonymsTest {
    Main main;

    @Rule
    public ActivityTestRule<Main> mActivityRule = new ActivityTestRule<>(Main.class);

    @Before
    public void setup() {
        main = (Main) mActivityRule.getActivity();
    }

    @Test
    public void search() {
        Locale locale = Locale.getDefault();
        Assert.assertEquals(Locale.US.getCountry(), locale.getCountry());
        List<PresetItem> result = SearchIndexUtils.searchInPresets(main, "raptor", ElementType.NODE, 2, 10);
        Assert.assertTrue(result.size() > 0);
        Assert.assertEquals("Animal shelter", result.get(0).getName());
    }
}
