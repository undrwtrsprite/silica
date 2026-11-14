# dmgbuild settings for the Silica installer window. Used by make_dmg.sh (-D app=<path to Silica.app> -D dmgdir=<launcher/dmg>).
import os
app = defines["app"]                      # noqa: F821  (dmgbuild injects `defines`)
here = defines["dmgdir"]                  # noqa: F821  (the launcher/dmg folder; dmgbuild gives settings no __file__)

format = "UDZO"
files = [app]
symlinks = {"Applications": "/Applications"}
badge_icon = os.path.join(os.path.dirname(here), "Icon", "Silica.icns")
background = os.path.join(here, "background.tiff")
window_rect = ((200, 140), (660, 400))     # position, size in points (matches the background)
icon_size = 112
text_size = 13
icon_locations = {"Silica.app": (170, 185), "Applications": (490, 185)}
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
default_view = "icon-view"
