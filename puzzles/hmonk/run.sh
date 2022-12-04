(
    cat <<'FOO'
hmonk
COMEFROM
/bin/umodem arith2.adv STOP
Add Z y => y;
Add (S x) y => S (Add x y);
{ Add (Add x y) z => Add x (Add y z); }
Mult Z y => Z;
Mult (S x) y => Add y (Mult x y);
{ Mult (Mult x y) z => Mult x (Mult y z); }
op (op x y) z => op x (op y z);
Mult (Add x y) z => Add (Mult x z) (Mult y z);
DUP z z => z;
x y z => x y (DUP z z);
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
advise arith arith2.adv arith2.tests
exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
