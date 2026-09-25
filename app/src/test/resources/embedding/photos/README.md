Sample photos used by `ClipModelsTest` to check that a search phrase finds the right picture. All are in the
public domain or released under CC0; they are shrunk copies of the sample data shipped with scikit-image.

| File | Subject | Source and terms |
| --- | --- | --- |
| `cat.jpg` | a cat | scikit-image `chelsea`: CC0 by the photographer, Stefan van der Walt |
| `astronaut.jpg` | an astronaut | scikit-image `astronaut`: NASA Great Images, no known copyright restrictions |
| `coffee.jpg` | a cup of coffee | scikit-image `coffee`: CC0 by the photographer, Rachel Michetti (Pikolo Espresso Bar) |
| `rocket.jpg` | a rocket launch | scikit-image `rocket`: SpaceX photo released into the public domain |

`tokenizer-cases.tsv`, `text-golden.tsv` and `image-golden.txt` are reference outputs produced with the
models' own reference tooling (the `tokenizers` library and ONNX Runtime for Python) for the checks in
`WordPieceTokenizerTest` and `ClipModelsTest`.
