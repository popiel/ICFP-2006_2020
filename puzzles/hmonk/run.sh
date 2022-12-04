(
    cat <<'FOO'
hmonk
COMEFROM
/bin/umodem arith2.adv STOP
Add x Z => x;
Add x (S y) => S (Add x y);
Mult x Z => Z;
Mult x (S y) => Add (Mult x y) x;
op x (op y z) => op (op x y) z;
Mult x (Add y z) => Add (Mult x y) (Mult x z);
Compute x => x;
DUP z z => z;
x y z => x y (DUP z z);
.
STOP
/bin/umodem arith3.adv STOP
Compute (Add x Z) => Compute x;
Compute (Add x (S y)) => S (Compute (Add x y));
Compute (Mult x Z) => Compute Z;
Compute (Mult x (S y)) => Compute (Add (Mult x y) x);
Compute (x y z) => x y (Compute z);
x (Compute y) => Compute (x y);
Compute x => x;
.
STOP
/bin/umodem arith2.tests STOP
Compute (Mult (Mult (S Z) (S (S Z))) (Mult (S (S Z)) (S (S Z)))) -> (S (S (S (S (S (S (S (S Z))))))));
Compute (Mult (Add (S (S Z)) (S (S (S Z)))) (Add (S (S (S Z))) (S (S Z)))) ->
(S (S (S (S (S
(S (S (S (S (S
(S (S (S (S (S
(S (S (S (S (S
(S (S (S (S (S
Z)))))))))))))))))))))))));
.
STOP
advise arith arith3.adv arith2.tests
mail
exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
