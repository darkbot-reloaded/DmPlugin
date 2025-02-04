package com.deeme.tasks;

import java.util.Arrays;

import com.deeme.types.VerifierChecker;
import com.deeme.types.backpage.Utils;
import com.deeme.tasks.autoshop.AutoShop;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.FeatureInfo;
import eu.darkbot.api.managers.AuthAPI;
import eu.darkbot.api.managers.ExtensionsAPI;
import eu.darkbot.api.utils.Inject;

@Feature(name = "AutoShop", description = "Auto Buy Items")
public class AutoShopDummy extends AutoShop {

    public AutoShopDummy(PluginAPI api) {
        this(api, api.requireAPI(AuthAPI.class));
    }

    @Inject
    public AutoShopDummy(PluginAPI api, AuthAPI auth) {
        super(api);

        if (!Arrays.equals(VerifierChecker.class.getSigners(), getClass().getSigners())) {
            throw new SecurityException();
        }

        VerifierChecker.requireAuthenticity(auth);

        ExtensionsAPI extensionsAPI = api.requireAPI(ExtensionsAPI.class);
        FeatureInfo<?> feature = extensionsAPI.getFeatureInfo(this.getClass());
        Utils.discordCheck(feature, auth.getAuthId());
        Utils.showDonateDialog(feature, auth.getAuthId());
    }
}
