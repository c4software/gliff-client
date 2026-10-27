package io.github.c4software.gliff.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import io.github.c4software.gliff.domain.session.SessionError

/** Every text the screens show. */
interface Strings {
    val appName: String get() = "gliff"
    val sshKey: String
    val addMachine: String
    val editMachine: String
    val noMachines: String
    val firstSteps: String
    val privateScreen: String
    val mirror: String
    fun mirrorOf(output: String): String
    val hostKeyNotSeen: String
    val edit: String
    val delete: String
    val clone: String
    val cloneMachine: String
    val closeTab: String
    val openAnother: String
    val sessionOpen: String
    val cancel: String
    val save: String
    val close: String
    val copy: String
    val copied: String
    val address: String
    val addressHint: String
    val addressError: String
    val outputName: String
    val outputHint: String
    val serverPath: String
    val keyTitle: String
    val keyExplain: String
    val keyLoading: String
    fun connecting(address: String): String
    val hostKeyTitle: String
    fun hostKeyExplain(address: String): String
    val hostKeyChangedTitle: String
    fun hostKeyChangedExplain(address: String): String
    val previousKey: String
    val trust: String
    val trustNewKey: String
    val failedTitle: String
    val incompatibleTitle: String
    val machineRemoved: String
    val serverClosed: String
    val retry: String
    val back: String
    val sessionEnded: String
    val options: String
    val scaleAuto: String
    val resolutionTitle: String
    val resolutionInherit: String
    val resolutionCustom: String
    val width: String
    val height: String
    val resolutionExplain: String
    val keyboardZoom: String
    val keyboardZoomExplain: String
    val directTouch: String
    val directTouchExplain: String
    val httpMirror: String
    val httpMirrorExplain: String
    val httpMirrorNoNetwork: String
    val mirrorControl: String
    val mirrorControlExplain: String
    val mirrorDark: String
    val mirrorDarkExplain: String
    val keepAwake: String
    val clipboard: String
    val clipboardExplain: String
    val keepAwakeExplain: String
    val desktopFullScreen: String
    val desktopFullScreenExplain: String
}

object English : Strings {
    override val sshKey = "SSH key"
    override val addMachine = "Add machine"
    override val editMachine = "Edit machine"
    override val noMachines = "No machine yet"
    override val firstSteps =
        "1. Copy this device's SSH key into ~/.ssh/authorized_keys on the remote machine.\n" +
            "2. Add the machine as user@host.\n" +
            "The remote needs gliff-server and a running Hyprland session."
    override val privateScreen = "Private screen"
    override val mirror = "Mirror"
    override fun mirrorOf(output: String) = "Mirror of $output"
    override val hostKeyNotSeen = "host key not seen yet"
    override val edit = "Edit"
    override val delete = "Delete"
    override val clone = "Clone"
    override val cloneMachine = "Clone machine"
    override val closeTab = "Close the session"
    override val openAnother = "Open another machine"
    override val sessionOpen = "Session open"
    override val cancel = "Cancel"
    override val save = "Save"
    override val close = "Close"
    override val copy = "Copy"
    override val copied = "Copied"
    override val address = "Address"
    override val addressHint = "user@host or user@host:port"
    override val addressError = "Expected user@host, optionally :port"
    override val outputName = "Output to mirror"
    override val outputHint = "e.g. DP-1; empty for the focused one"
    override val serverPath = "gliff-server path on the remote"
    override val keyTitle = "This device's SSH key"
    override val keyExplain = "Append this line to ~/.ssh/authorized_keys on each remote machine."
    override val keyLoading = "Creating the key…"
    override fun connecting(address: String) = "Connecting to $address…"
    override val hostKeyTitle = "Unknown machine"
    override fun hostKeyExplain(address: String) =
        "First connection to $address. Check that this fingerprint is the machine's own " +
            "(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub) before trusting it."
    override val hostKeyChangedTitle = "The machine's key changed"
    override fun hostKeyChangedExplain(address: String) =
        "$address shows another key than the one you trusted. The machine may have been " +
            "reinstalled, or someone is intercepting the connection. Trust the new key only if you know why it changed."
    override val previousKey = "Trusted until now"
    override val trust = "Trust"
    override val trustNewKey = "Trust the new key"
    override val failedTitle = "Connection lost"
    override val incompatibleTitle = "Incompatible versions"
    override val machineRemoved = "This machine was removed."
    override val serverClosed = "The server closed the connection."
    override val retry = "Retry"
    override val back = "Back"
    override val sessionEnded = "Session ended"
    override val options = "Options"
    override val scaleAuto = "Auto"
    override val resolutionTitle = "Resolution"
    override val resolutionInherit = "As in options"
    override val resolutionCustom = "Custom"
    override val width = "Width"
    override val height = "Height"
    override val resolutionExplain =
        "Auto follows the window. A fixed resolution larger than the screen shows more, fitted in it."
    override val keyboardZoom = "Show as much with the keyboard"
    override val keyboardZoomExplain = "Draws the remote smaller while the keyboard is up, instead of cutting the screen."
    override val directTouch = "Tap where you click"
    override val directTouchExplain = "Off: the screen works as a trackpad. A button in the session switches at any time."
    override val httpMirror = "Web mirror"
    override val httpMirrorExplain =
        "Shows the open session in a web browser on the same network, sized for the browser's window: " +
            "zoom the page out to see more. Anyone who has this address sees your screen: keep it to yourself."
    override val httpMirrorNoNetwork = "No network address yet."
    override val clipboard = "Shared clipboard"
    override val clipboardExplain = "Text copied here can be pasted on the remote, and text copied there lands on this device's clipboard."
    override val keepAwake = "Keep the screen on"
    override val keepAwakeExplain = "The device does not go to sleep while a session is on screen."
    override val desktopFullScreen = "Full screen on a desktop"
    override val desktopFullScreenExplain = "A machine opened from a desktop window takes the whole display. Its header gives the window back."
    override val mirrorControl = "Control from the browser"
    override val mirrorControlExplain = "The page's mouse and keyboard drive the session. The address then gives control, not just a view."
    override val mirrorDark = "Dark screen while mirrored"
    override val mirrorDarkExplain = "This device's screen goes black while a browser watches. A touch brings it back for a moment."
}

object French : Strings {
    override val sshKey = "Clé SSH"
    override val addMachine = "Ajouter une machine"
    override val editMachine = "Modifier la machine"
    override val noMachines = "Aucune machine"
    override val firstSteps =
        "1. Copiez la clé SSH de cet appareil dans ~/.ssh/authorized_keys sur la machine distante.\n" +
            "2. Ajoutez la machine sous la forme user@host.\n" +
            "La machine distante doit avoir gliff-server et une session Hyprland ouverte."
    override val privateScreen = "Écran privé"
    override val mirror = "Miroir"
    override fun mirrorOf(output: String) = "Miroir de $output"
    override val hostKeyNotSeen = "clé d'hôte pas encore vue"
    override val edit = "Modifier"
    override val delete = "Supprimer"
    override val clone = "Cloner"
    override val cloneMachine = "Cloner la machine"
    override val closeTab = "Fermer la session"
    override val openAnother = "Ouvrir une autre machine"
    override val sessionOpen = "Session ouverte"
    override val cancel = "Annuler"
    override val save = "Enregistrer"
    override val close = "Fermer"
    override val copy = "Copier"
    override val copied = "Copié"
    override val address = "Adresse"
    override val addressHint = "user@host ou user@host:port"
    override val addressError = "Attendu : user@host, avec :port en option"
    override val outputName = "Sortie à refléter"
    override val outputHint = "ex. DP-1 ; vide pour celle qui a le focus"
    override val serverPath = "Chemin de gliff-server sur la machine"
    override val keyTitle = "Clé SSH de cet appareil"
    override val keyExplain = "Ajoutez cette ligne à ~/.ssh/authorized_keys sur chaque machine distante."
    override val keyLoading = "Création de la clé…"
    override fun connecting(address: String) = "Connexion à $address…"
    override val hostKeyTitle = "Machine inconnue"
    override fun hostKeyExplain(address: String) =
        "Première connexion à $address. Vérifiez que cette empreinte est bien celle de la machine " +
            "(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub) avant de lui faire confiance."
    override val hostKeyChangedTitle = "La clé de la machine a changé"
    override fun hostKeyChangedExplain(address: String) =
        "$address présente une autre clé que celle que vous aviez acceptée. La machine a peut-être été " +
            "réinstallée, ou quelqu'un intercepte la connexion. N'acceptez la nouvelle clé que si vous savez pourquoi elle a changé."
    override val previousKey = "Acceptée jusqu'ici"
    override val trust = "Faire confiance"
    override val trustNewKey = "Accepter la nouvelle clé"
    override val failedTitle = "Connexion perdue"
    override val incompatibleTitle = "Versions incompatibles"
    override val machineRemoved = "Cette machine a été supprimée."
    override val serverClosed = "Le serveur a fermé la connexion."
    override val retry = "Réessayer"
    override val back = "Retour"
    override val sessionEnded = "Session terminée"
    override val options = "Options"
    override val scaleAuto = "Auto"
    override val resolutionTitle = "Résolution"
    override val resolutionInherit = "Comme les options"
    override val resolutionCustom = "Autre"
    override val width = "Largeur"
    override val height = "Hauteur"
    override val resolutionExplain =
        "Auto suit la fenêtre. Une résolution fixe plus grande que l'écran en montre plus, ajustée dedans."
    override val keyboardZoom = "En montrer autant avec le clavier"
    override val keyboardZoomExplain = "Dessine le bureau plus petit quand le clavier est ouvert, au lieu de couper l'écran."
    override val directTouch = "Cliquer là où l'on touche"
    override val directTouchExplain = "Désactivé : l'écran sert de pavé tactile. Un bouton de la session permet de changer à tout moment."
    override val httpMirror = "Miroir web"
    override val httpMirrorExplain =
        "Affiche la session ouverte dans un navigateur du même réseau, à la taille de sa fenêtre : " +
            "dézoomez la page pour en voir plus. Quiconque a cette adresse voit votre écran : gardez-la pour vous."
    override val httpMirrorNoNetwork = "Pas encore d'adresse réseau."
    override val clipboard = "Presse-papiers partagé"
    override val clipboardExplain = "Le texte copié ici se colle sur la machine distante, et le texte copié là-bas arrive dans le presse-papiers de cet appareil."
    override val keepAwake = "Désactiver la veille"
    override val keepAwakeExplain = "L'appareil ne se met pas en veille tant qu'une session est à l'écran."
    override val desktopFullScreen = "Plein écran sur un bureau"
    override val desktopFullScreenExplain = "Une machine ouverte depuis une fenêtre de bureau prend tout l'écran. Son en-tête la remet en fenêtre."
    override val mirrorControl = "Contrôle depuis le navigateur"
    override val mirrorControlExplain = "La souris et le clavier de la page pilotent la session. L'adresse donne alors le contrôle, plus seulement la vue."
    override val mirrorDark = "Écran noir pendant le miroir"
    override val mirrorDarkExplain = "L'écran de cet appareil passe au noir tant qu'un navigateur regarde. Un appui le rallume un moment."
}

@Composable
fun rememberStrings(): Strings {
    val language = Locale.current.language
    return remember(language) { if (language == "fr") French else English }
}

/** What to say of a session that stopped; what the other end said is shown as it is. */
fun Strings.failure(error: SessionError): String = when (error) {
    SessionError.MachineRemoved -> machineRemoved
    SessionError.ServerClosed -> serverClosed
    is SessionError.Incompatible -> error.detail
    is SessionError.Connection -> error.detail
}
