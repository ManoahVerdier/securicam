# Guide d'exemption de batterie pour Securicam

## Pourquoi c'est nécessaire ?

Android limite les applications en arrière-plan pour économiser la batterie. Pour que Securicam fonctionne de manière fiable en continu, il est nécessaire de désactiver ces optimisations.

## Paramètres généraux Android

1. Ouvrez **Paramètres** > **Applications** > **Securicam**
2. Sélectionnez **Batterie** ou **Optimisation de la batterie**
3. Choisissez **Ne pas optimiser** ou **Illimité**

## Instructions par fabricant

### Samsung (One UI)

1. **Paramètres** > **Entretien de l'appareil** > **Batterie**
2. Appuyez sur **Limites d'utilisation en arrière-plan**
3. Ajoutez **Securicam** à **Applications jamais mises en veille**
4. Dans **Paramètres** > **Applications** > **Securicam**
5. **Batterie** > **Autoriser l'activité en arrière-plan**

### Xiaomi (MIUI)

1. **Paramètres** > **Applications** > **Gérer les applications** > **Securicam**
2. **Économiseur de batterie** > **Aucune restriction**
3. **Paramètres** > **Batterie et performances** > **Comportement de l'application**
4. Sélectionnez **Securicam** et choisissez **Pas de restriction**
5. **Sécurité** > **Autostart** > Activez **Securicam**

### Huawei (EMUI)

1. **Paramètres** > **Applications** > **Securicam**
2. **Consommation d'énergie** > **Désactiver la gestion automatique**
3. Activez **Lancement automatique**, **Exécution en arrière-plan**, **Exécution en second plan**
4. **Paramètres** > **Batterie** > **Lancement d'applications**
5. Désactivez **Gestion automatique** pour Securicam

### Oppo/Realme (ColorOS)

1. **Paramètres** > **Batterie** > **Économie d'énergie**
2. Ajoutez **Securicam** aux **Applications protégées**
3. **Paramètres** > **Gestion des applications** > **Securicam**
4. **Économie d'énergie** > **Autoriser l'activité en arrière-plan**
5. **Démarrage automatique** > Activez pour Securicam

### OnePlus (OxygenOS)

1. **Paramètres** > **Batterie** > **Optimisation de la batterie**
2. Sélectionnez **Toutes les applications** dans le menu déroulant
3. Trouvez **Securicam** et sélectionnez **Ne pas optimiser**
4. **Paramètres** > **Applications** > **Securicam** > **Batterie**
5. Sélectionnez **Illimité**

### Google Pixel (Android Stock)

1. **Paramètres** > **Applications** > **Securicam**
2. **Batterie** > **Illimité**
3. C'est tout ! Android stock est moins agressif

### Asus (ROG/ZenUI)

1. **Paramètres** > **Gestion de l'alimentation** > **PowerMaster**
2. Ajoutez **Securicam** à la **Liste blanche**
3. **Paramètres** > **Applications** > **Securicam** > **Batterie**
4. Désactivez l'optimisation

## Vérification

Après configuration :

1. Démarrez le streaming sur Securicam
2. Verrouillez l'écran
3. Attendez 5-10 minutes
4. Vérifiez que le streaming est toujours actif sur le frontend Angular

## Conseils supplémentaires

- **Branchez le téléphone en permanence** pour éviter les problèmes de batterie
- **Évitez la surchauffe** : ne couvrez pas le téléphone et placez-le dans un endroit ventilé
- **Désactivez les mises à jour automatiques** pour éviter les redémarrages intempestifs
- **Activez le démarrage automatique** dans Securicam pour récupérer après un redémarrage

## Problèmes courants

### Le service s'arrête après quelques minutes
→ Vérifiez tous les paramètres ci-dessus pour votre fabricant

### Le streaming ne reprend pas après un redémarrage
→ Activez l'option "Auto-start on boot" dans Securicam
→ Vérifiez que l'autostart est activé dans les paramètres système

### La qualité vidéo se dégrade
→ Le téléphone peut surchauffer, améliorez la ventilation
→ Réduisez la résolution dans les paramètres de l'app
