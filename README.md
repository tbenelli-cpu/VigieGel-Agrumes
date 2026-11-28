# 🍊❄️ VigieGel Agrumes

Application Android gratuite (sans publicité, sans compte et sans collecte de données) conçue pour anticiper les risques de gel nocturne sur les **agrumes en pot** et les plantes sensibles en extérieur.

## 📲 Téléchargement et installation (Android 8.0+)

👉 **[Télécharger la dernière version (VigieGel-Agrumes.apk)](https://github.com/tbenelli-cpu/VigieGel-Agrumes/releases/latest/download/VigieGel-Agrumes.apk)**

### Guide rapide d'installation
1. Cliquez sur le lien ci-dessus depuis votre téléphone Android pour télécharger `VigieGel-Agrumes.apk`.
2. Ouvrez le fichier depuis vos **Téléchargements / Mes fichiers**.
3. Si Android le demande, autorisez l'installation depuis cette source (**Paramètres** $\rightarrow$ **Autoriser depuis cette source**).
4. *(Smartphones Samsung récents)* Si l'installation est bloquée par **Blocage automatique**, désactivez temporairement **Paramètres** $\rightarrow$ **Sécurité et confidentialité** $\rightarrow$ **Blocage automatique**, installez l'application, puis réactivez-le si souhaité.

---

## ✨ Fonctionnalités principales (v1.12)

* **Modèles haute résolution Météo-France (AROME 1,3 km + ARPEGE)** pour la France et l'Europe, avec relais automatique **ECMWF / ICON** pour une tendance complète sur **6 jours** (`🟢 Pas de gel` / `🟠 Risque de gel` / `🔴 Il va geler`).
* **Calcul du refroidissement radiatif au niveau des pots** : prend en compte la couverture nuageuse et la vitesse du vent pour estimer la température réelle subie par le feuillage (souvent 1,5 à 2 °C plus basse que la température sous abri à 2 m).
* **Conseils de protection adaptés** : alerte visuelle graduée avec recommandations concrètes (voile d'hivernage P30, double voile P30/P60, mise à l'abri des variétés les plus sensibles).
* **Étalonnage fin par jardin (`⚙️ Paramètres`)** :
  * Ajustement au dixième de degré (`-0,5 °C` / `-0,1 °C` / `+0,1 °C` / `+0,5 °C`) mémorisé individuellement pour chaque lieu favori.
  * Rappel direct des températures brutes Météo-France dans la fenêtre d'étalonnage : **relevé actuel en direct**, **minimale ce matin** et **prévision figée la veille au soir**.
* **Alerte quotidienne intelligente** : notification en fin de journée (heure réglable) uniquement lorsqu'un risque de gel est prévu pour la nuit à venir.
* **Lieux favoris (grille 2×2)** : bascule instantanée entre plusieurs jardins ou emplacements (avec conservation de l'étalonnage propre à chaque lieu).
* **Multilingue** : interface disponible en **Français**, **Italien** et **Anglais**.

---

## 🔒 Confidentialité

Toutes les préférences (lieux favoris, étalonnage local, heure d'alerte) sont stockées **uniquement en local sur votre smartphone**. Les coordonnées GPS ou le nom de la commune recherchée sont uniquement transmis de manière anonyme à l'API météo [Open-Meteo](https://open-meteo.com/) pour récupérer les prévisions.