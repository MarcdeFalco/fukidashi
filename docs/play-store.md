# Fiche Play Store – Fukidashi

Langue par défaut de la fiche : **anglais (en-US)**, avec une traduction **français (fr-FR)**
(Play Console → Présence sur le Play Store → Fiche principale → Gérer les traductions).

## Identité
- **Identifiant** : `app.fukidashi` (définitif après la 1re publication)
- **Catégorie** : Éducation / Education
- **Prix** : gratuit, sans publicité ni achat intégré
- **Contenu** : tout public (classification IARC à remplir dans la console)
- **Développeur affiché** : Atelier Hanko

---

## English (en-US)

**App name** (30 max): Fukidashi – Read Japanese

**Short description** (80 max):
Read manga in Japanese: furigana, dictionary and grammar, all offline.

**Full description:**
Fukidashi (吹き出し, "speech bubble") helps you read Japanese manga and books.

Open a page (image, CBZ archive, shared screenshot) or photograph your paper book: the app finds
the speech bubbles. Tap a bubble to study it:

• The text, with furigana above the kanji (can be hidden for practice)
• Every word is tappable: reading, dictionary form, conjugation breakdown
  (備えていた = 備える + て + いる + た), definitions from JMdict
• Kanji details: meanings, on / kun readings, JLPT level, stroke count
• The grammar points of the sentence (particles, verb forms, set phrases), explained in plain
  English with their JLPT level, and highlighted in the sentence

For paper books, the camera takes the picture by itself as soon as the phone is steady, for
sharp photos.

Everything works offline, on your phone: no data is ever sent. Accelerated by the NPU of
Snapdragon chips, works on all recent Android phones. Available in English and French.

Free software (AGPL-3.0 license). JMdict and KANJIDIC2 dictionaries by the EDRDG (CC BY-SA 4.0).
Sample pages in the screenshots: “ブラックジャックによろしく” by 佐藤秀峰 (Shūhō Satō), free for reuse.

**Graphics:** `docs/store/icon-512.png`, `docs/store/en/feature-graphic.png`,
`docs/store/en/screenshot-1.png` … `-4.png`

---

## Français (fr-FR)

**Nom** (30 max) : Fukidashi – Lire le japonais

**Description courte** (80 max) :
Lisez vos mangas en japonais : furigana, dictionnaire et grammaire, hors ligne.

**Description longue :**
Fukidashi (吹き出し, « bulle ») vous aide à lire les mangas et livres japonais.

Ouvrez une page (image, archive CBZ, capture partagée) ou photographiez votre livre papier :
l'application repère les bulles de texte. Touchez une bulle pour l'étudier :

• Le texte, avec les furigana au-dessus des kanji (masquables pour s'entraîner)
• Chaque mot se touche : lecture, forme du dictionnaire, conjugaison décomposée
  (備えていた = 備える + て + いる + た), définitions en français ou en anglais (JMdict)
• Le détail des kanji : sens, lectures on / kun, niveau JLPT, nombre de traits
• Les points de grammaire de la phrase (particules, formes verbales, tournures),
  expliqués en français avec leur niveau JLPT, et surlignés dans la phrase

Pour les livres papier, l'appareil photo se déclenche tout seul quand le téléphone est
immobile, pour des photos nettes.

Tout fonctionne hors ligne, sur votre téléphone : aucune donnée n'est envoyée.
Accéléré par le NPU des puces Snapdragon, compatible avec tous les Android récents.
Disponible en français et en anglais.

Logiciel libre (licence AGPL-3.0). Dictionnaires JMdict et KANJIDIC2 de l'EDRDG (CC BY-SA 4.0).
Pages d'exemple des captures : « ブラックジャックによろしく », 佐藤秀峰 (œuvre en libre réutilisation).

**Visuels :** `docs/store/icon-512.png`, `docs/store/fr/feature-graphic.png`,
`docs/store/fr/screenshot-1.png` … `-4.png`

---

## Sécurité des données (formulaire de la console)
- Données collectées : **aucune**
- Données partagées : **aucune**
- Chiffrement en transit : sans objet (pas de réseau)
- Suppression des données : les photos restent dans l'espace privé de l'appli ;
  désinstaller l'appli efface tout

## Politique de confidentialité
https://marcdefalco.github.io/fukidashi/privacy.html (anglais et français sur la même page ;
fichier `docs/privacy.html`, servi par GitHub Pages : Settings → Pages → branche `main`, dossier `/docs`).

## Captures d'écran : conditions de l'œuvre
Captures 1080×2160 faites sur émulateur avec la page 50 du volume 1 de
**« ブラックジャックによろしく » de 佐藤秀峰**, en libre réutilisation, y compris commerciale
(conditions : https://densho810.com/free/).
- Obligation : mentionner le titre et l'auteur (fait dans les deux descriptions longues) ;
- Obligation : **signaler l'utilisation par e-mail à info@densho810.com dans le mois** suivant
  la publication de la fiche.
- Ne pas utiliser d'autres pages de mangas commerciaux.

Visuels régénérables : `.venv/bin/python export/store_graphics.py` (icône, images de présentation).

## Avant la production (compte personnel récent)
Test fermé obligatoire : au moins 12 testeurs inscrits pendant 14 jours consécutifs.
