import {ref} from 'vue'
import {errorMessage} from '@shtong/web-shared'

export function useAction(){
  const busy=ref(false),error=ref(''),notice=ref('')
  async function run(action:()=>Promise<void>){
    if(busy.value)return
    busy.value=true;error.value='';notice.value=''
    try{await action()}catch(problem){error.value=errorMessage(problem)}finally{busy.value=false}
  }
  return {busy,error,notice,run}
}
