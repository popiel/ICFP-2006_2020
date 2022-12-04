(
    cat <<'FOO'
yang
U+262F

exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
