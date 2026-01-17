#!/bin/bash
set -e

# Arguments
OS=$1
ARCH=$2
ARTIFACT_PATH=$3
MAVEN_REPO_URL=$4
MAVEN_USERNAME=$5
MAVEN_PASSWORD=$6
VERSION=$7
GROUP_ID="com.jetbrains"
ARTIFACT_ID="jcef"

if [ -z "$VERSION" ]; then
    VERSION="1.0"
fi

if [ -z "$OS" ] || [ -z "$ARCH" ] || [ -z "$ARTIFACT_PATH" ] || [ -z "$MAVEN_REPO_URL" ]; then
    echo "Usage: $0 <OS> <ARCH> <ARTIFACT_PATH> <MAVEN_REPO_URL> [MAVEN_USERNAME] [MAVEN_PASSWORD] [VERSION]"
    exit 1
fi

echo "Deploying $OS $ARCH from $ARTIFACT_PATH"

# Create a temporary directory for extraction
EXTRACT_DIR=$(mktemp -d)
tar -xzf "$ARTIFACT_PATH" -C "$EXTRACT_DIR"

pushd "$EXTRACT_DIR" > /dev/null

# Check if jmods exists to apply the user's logic
if [ -d "jmods" ]; then
    echo "Processing jmods structure..."
    cd jmods
    
    # Verify jmod command
    if ! command -v jmod &> /dev/null; then
        # Try to find jmod in JAVA_HOME if set
        if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/jmod" ]; then
            # We can't set alias in script easily for non-interactive, so just define a var
            JMOD_CMD="$JAVA_HOME/bin/jmod"
        else
            echo "Error: jmod command not found and JAVA_HOME not set or invalid."
            exit 1
        fi
    else
        JMOD_CMD="jmod"
    fi

    # Extract jmods
    for mod in jcef gluegen.rt jogl.all; do
        if [ -f "${mod}.jmod" ]; then
             echo "Extracting ${mod}.jmod..."
             "$JMOD_CMD" extract "${mod}.jmod"
        fi
    done
    
    # Create jcef.jar containing classes
    if [ -d "classes" ]; then
        cd classes
        jar --create --file jcef.jar .
        mv jcef.jar ..
        cd ..
    else
        echo "Error: classes directory not found after jmod extraction"
        exit 1
    fi

    # Prepare specific folder
    PLATFORM_DIR="${OS}-${ARCH}"
    mkdir -p "$PLATFORM_DIR"
    
    # Move bin and lib if they exist
    if [ -d "bin" ]; then mv bin "$PLATFORM_DIR/bin"; fi
    if [ -d "lib" ]; then mv lib "$PLATFORM_DIR/lib"; fi

    # Move Frameworks if it exists (macOS specific)
    if [ "$OS" == "macos" ] && [ -d "../Frameworks" ]; then
        mv "../Frameworks" "$PLATFORM_DIR/Frameworks"
    fi

    # Create platform jar
    cd "$PLATFORM_DIR"
    NATIVE_JAR_NAME="jcef-${PLATFORM_DIR}.jar"
    jar --create --file "$NATIVE_JAR_NAME" .
    mv "$NATIVE_JAR_NAME" ..
    cd ..
    
    OUT_JAR_DIR="$EXTRACT_DIR/jmods"
    MAIN_JAR_NAME="jcef.jar"
    CLASSIFIER_JAR_NAME="$NATIVE_JAR_NAME"
    
else
    # Fallback/Standard logic if jmods directory is missing
    echo "Error: jmods directory not found."
    exit 1
fi

popd > /dev/null

echo "Main Jar: $OUT_JAR_DIR/$MAIN_JAR_NAME"
echo "Classifier Jar: $OUT_JAR_DIR/$CLASSIFIER_JAR_NAME"

if [ ! -f "$OUT_JAR_DIR/$MAIN_JAR_NAME" ]; then
    echo "Error: Main jar file does not exist at expected path."
    exit 1
fi
if [ -n "$CLASSIFIER_JAR_NAME" ] && [ ! -f "$OUT_JAR_DIR/$CLASSIFIER_JAR_NAME" ]; then
    echo "Warning: Classifier jar file does not exist at expected path."
    exit 1
fi

# Prepare Maven settings with credentials if provided
SETTINGS_XML="$EXTRACT_DIR/settings.xml"
if [ -n "$MAVEN_USERNAME" ] && [ -n "$MAVEN_PASSWORD" ]; then
    cat > "$SETTINGS_XML" <<EOF
<settings>
  <servers>
    <server>
      <id>deployment-repo</id>
      <username>${MAVEN_USERNAME}</username>
      <password>${MAVEN_PASSWORD}</password>
    </server>
  </servers>
</settings>
EOF
    SETTINGS_ARG="-s $SETTINGS_XML"
    REPO_ID="deployment-repo"
else
    SETTINGS_ARG=""
    REPO_ID="deployment-repo" 
fi

# Deploy Logic

# 1. Deploy with classifier (The platform specific jar)
CLASSIFIER="${OS}-${ARCH}"
echo "Deploying classifier artifact: $CLASSIFIER"

pushd "$OUT_JAR_DIR" > /dev/null
mvn deploy:deploy-file $SETTINGS_ARG \
    -Durl="$MAVEN_REPO_URL" \
    -DrepositoryId="$REPO_ID" \
    -Dfile="$CLASSIFIER_JAR_NAME" \
    -DgroupId="$GROUP_ID" \
    -DartifactId="$ARTIFACT_ID" \
    -Dversion="$VERSION" \
    -Dclassifier="$CLASSIFIER" \
    -Dpackaging="jar" \
    -DgeneratePom=false
popd > /dev/null

# 2. Special case for linux-x86_64: Deploy the main artifact (no classifier)
if [ "$OS" == "linux" ] && [ "$ARCH" == "x86_64" ]; then
    echo "Deploying as main artifact (linux-x86_64)"
    
    pushd "$OUT_JAR_DIR" > /dev/null
    mvn deploy:deploy-file $SETTINGS_ARG \
        -Durl="$MAVEN_REPO_URL" \
        -DrepositoryId="$REPO_ID" \
        -Dfile="$MAIN_JAR_NAME" \
        -DgroupId="$GROUP_ID" \
        -DartifactId="$ARTIFACT_ID" \
        -Dversion="$VERSION" \
        -Dpackaging="jar"
    popd > /dev/null
fi

# Cleanup
rm -rf "$EXTRACT_DIR"

echo "Deployment finished."
