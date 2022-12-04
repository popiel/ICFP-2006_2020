(
    cat <<'FOO'
howie
xyzzy
adventure
ex pamphlet
ex manifesto
n
get bolt
get spring
inc spring
get button
get processor
get pill
inc pill
get radio
get cache
combine processor cache
get blue transistor
combine radio blue transistor
get antenna
inc antenna
get screw
get motherboard
combine motherboard screw
get a-1920-ixb
combine a-1920-ixb processor
combine a-1920-ixb bolt
combine a-1920-ixb radio
get transistor
combine a-1920-ixb transistor
combine motherboard a-1920-ixb
get keypad
combine keypad motherboard
combine keypad button
get trash
ex trash
inc trash
s
get pamphlet
get manifesto
use keypad
switch goggles to ml
look

exit
exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
