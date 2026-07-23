def call(String diffrange){
    def output=sh(
        script """
            set -euo pipefail // -e : stop the code if any error accure , -u : return error if the variable is empty , o- :check all the commands not only the final one 
            declare -A ROOTS=() // declare empty associative array 

            while IFS= read  -r FILE ;do // -r: read it as it is 
            [-z '$FILE']&& continue // -z: check if empty
            case '$FILE' in
              apis/*) ;;
              *) continue ;;
            esac
            DIR=$(dirname "$FILE") // move up
            while ["$DIR" != "apis" ]&& ["$DIR"!= "."]&&[-n "$DIR"];do //-n: check if not empty 
                if[-f "$DIR/pom.xml"] && [-f "$DIR/deployment/docker/Dockerfile"];then // -f : does this file exist in there
                    REL = "${DIR#apis}" // remove the first word 
                    ROOTS["$REL"]=1
                    break
                fi
                DIR=$(dirname "$DIR")
            done
            done<<(git diff --name-only ${diffRange}--'apis/**' 2>/dev/null || true)

            if [${#ROOTS[@]}-eq 0];then   //@: mean all the elements , #: count
                echo '[]'
            else
                printf '%s\\n' "\${!ROOTS[@]}" \  //! give me all the keys
                | sort \
                | jq -R -s -c 'split("\\n") | map(select(length > 0)) | map({path: ., slug: (split("/") | join("-"))})' // jq: turn text to json ,-R: expect raw text,-s:readed as a single line,-c:compact the output to one line , split("/n") convert to array
            fi
        """,
        returnstdout=true).trim()
    if(!output){
        return []
    }
    return readJSON(text:output)
}
return this