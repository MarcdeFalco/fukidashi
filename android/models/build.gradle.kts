// Pack de ressources Play (Play Asset Delivery) : modèles et dictionnaire, ~330 Mo,
// installés avec l'appli. Le module de base doit rester sous 200 Mo.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
