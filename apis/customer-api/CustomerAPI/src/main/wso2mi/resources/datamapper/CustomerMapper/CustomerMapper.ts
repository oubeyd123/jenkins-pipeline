import * as dmUtils from "./dm-utils";
declare var DM_PROPERTIES: any;

/*
 * inputType : "JSON",
 */
interface Root {
    firstName: string;
    lastName: string;
    age: number;
}

/*
* outputType : "JSON",
*/
interface OutputRoot {
    customerName: string
    customerAge: number
}



/**
 * functionName : map_S_Root_S_Root
 * inputVariable : inputRoot
*/
export function mapFunction(input: Root): OutputRoot {
    return {
        customerName: input.firstName + input.lastName,
        customerAge: input.age
    }
}

