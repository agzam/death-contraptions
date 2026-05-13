Scaffold a new Emacs package. The package name is: $ARGUMENTS

If the package name above is blank, ask the user for one before proceeding.

Use the `elisp-eval` tool to call:

```elisp
(create-emacs-package "PACKAGE-NAME")
```

The function returns the created directory path. Report it to the user and remind them to update the TODO markers in the main `.el` file and `README.org`.
