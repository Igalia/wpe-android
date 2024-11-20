/^##\s+/ {
    if (extracting)
        exit;
    extracting = 1;
    next;
}

{
    if (extracting)
        print;
}
