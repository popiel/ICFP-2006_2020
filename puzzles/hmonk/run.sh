(
    cat <<'FOO'
hmonk
COMEFROM
ls
cat README
cat advise.man
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
Compute (Mult x Z) => Compute Z;
Compute (Add x (S y)) => S (Compute (Add x y));
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
/bin/umodem xml.adv STOP
SNF (Tag q (Tag q d)) => SNF (Tag q d);
SNZ (Tag q (Tag q d)) => SNZ (Tag q d);
SNF (Tag q (Tag Bold x)) => SNF (Tag Bold (Tag q x));
SNF (Tag Maj (Tag Emph x)) => SNF (Tag Emph (Tag Maj x));
SNZ (Tag q (Tag Bold x)) => SNZ (Tag Bold (Tag q x));
SNZ (Tag Maj (Tag Emph x)) => SNZ (Tag Emph (Tag Maj x));
SNF (Tag q d) => Tag q (SNF d);
SNZ (Seq (Tag q x) (Tag q y)) => Tag q (SNZ (Seq x y));
SNZ (Seq (Tag q x) (Seq (Tag q y) z)) => Seq (Tag q (SNZ (Seq x y))) z;
Tag q (SNF (Seq x y)) => SNF (Seq (Tag q x) (Tag q y));
Seq (SNF (Seq x y)) z => SNF (Seq x (Seq y z));
SNF (Seq x y) => Seq (SNF x) y;
Seq (SNZ x) y => Seq x (SNF y);
Seq (Seq x y) (SNZ z) => SNF (Seq x (Seq y z));
op x (SNZ y) => SNZ (op x y);
SNF x => SNZ x;
SNZ x => x;
.
STOP
/bin/umodem xml.tests STOP
SNF (Tag Bold (Tag Bold (Seq A B))) -> Tag Bold (Seq A B);
SNF (Tag Maj (Tag Bold (Seq A B))) -> Tag Bold (Tag Maj (Seq A B));
SNF (Seq (Seq A B) A) -> Seq A (Seq B A);
SNF (Tag Emph (Tag Emph (Seq (Seq (Tag Emph (Tag Bold B)) (Seq (Seq B A) (Tag Maj B))) (Tag Bold (Tag Emph (Seq B B)))))) ->
Seq (Tag Bold (Tag Emph B)) (Seq (Tag Emph (Seq B (Seq A (Tag Maj B)))) (Tag Bold (Tag Emph (Seq B B))));
.
STOP
advise xml xml.adv xml.tests
exit
FOO
) |
java -jar ../../jvm/target/um.jar ../../codex.um | tee run.out
