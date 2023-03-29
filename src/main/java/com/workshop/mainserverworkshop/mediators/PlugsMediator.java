package com.workshop.mainserverworkshop.mediators;

import com.workshop.mainserverworkshop.DB.PlugRepoController;
import com.workshop.mainserverworkshop.DB.PlugSave;
import com.workshop.mainserverworkshop.engine.Plug;
import com.workshop.mainserverworkshop.engine.modes.GenericMode;
import com.workshop.mainserverworkshop.engine.modes.IModeListener;
import okhttp3.*;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RestController
public class PlugsMediator { //this mediator sends http requests to the plugs(the main server behaves here as client)
    public final int SAFE_MODE_LIST = 0;
    public final int SLEEP_MODE_LIST = 1;
    private final int MAX_PLUGS = 9;
    private static PlugsMediator instance = null;
    private final List<Plug> plugsList;
    private final List<Boolean> indexesFreeList;
    private final OkHttpClient httpClient;
    private final List<List<IModeListener>> signedUpPlugsForModesList;
    private static PlugRepoController plugRepoController;
    //private PlugRepository plugRepository;

    public static void UpdatePlugController(PlugRepoController plugRepoController) {
        PlugsMediator.plugRepoController = plugRepoController;
    }

    private PlugsMediator() {
        plugsList = new ArrayList<>(MAX_PLUGS);
        signedUpPlugsForModesList = new ArrayList<>();
        signedUpPlugsForModesList.add(new ArrayList<>());   //for safe list
        signedUpPlugsForModesList.add(new ArrayList<>());   //for sleep list
        httpClient = new OkHttpClient();
        indexesFreeList = new ArrayList<>(MAX_PLUGS);
        for(int i = 0; i < MAX_PLUGS; i++){indexesFreeList.add(true);}
    }

    public boolean AddNewPlug(Process i_Process, int i_Port, String i_PlugTitle, int i_UiIndex, String i_PlugType, int i_MinElectricityVolt, int i_MaxElectricityVolt)
    {
        boolean res = false;
        int availableInternalIndex = findFirstAvailableInternalIndexForNewPlug();
        if(availableInternalIndex != -1){
            indexesFreeList.set(availableInternalIndex, false);
            Plug newPlug = new Plug(i_Process, i_Port,i_PlugTitle, i_PlugType, this,availableInternalIndex,i_UiIndex,i_MinElectricityVolt, i_MaxElectricityVolt);
            plugsList.add(availableInternalIndex,newPlug);
            SavePlugToDB(newPlug);
            res = true;
        }

        return res;
    }

    private int findFirstAvailableInternalIndexForNewPlug()
    {
        int i, res = -1;
        for(i = 0; i < indexesFreeList.size(); i++){
            if(indexesFreeList.get(i)){
                res = i;
                break;
            }
        }
        return res;
    }

    public static PlugsMediator getInstance() {
        if (instance == null) {
            instance = new PlugsMediator();
        }
        return instance;
    }

    public Plug GetPlugAccordingToUiIndex(int i_UiIndex) {
        AtomicReference<Plug> res = new AtomicReference<>();
        boolean found = false;
        for (Plug p : getPlugsList()) {
            if (p.getUiIndex() == i_UiIndex) {
                res.set(p);
                found = true;
                break;
            }
        }
        return found ? res.get() : null;
    }

//    public Plug getPlugAccordingToInternalIndex(int i_InternalIndex) {
//        return getPlugsList().get(i_InternalIndex);
//    }

    public List<Plug> getPlugsList() {
        return getInstance().plugsList;
    }

    public void addModeListener(IModeListener i_ModeListener, int i_ModeType) {
        signedUpPlugsForModesList.get(i_ModeType).add(i_ModeListener);
    }

    public void removeModeListener(IModeListener i_ModeListener, int i_ModeType) {
        signedUpPlugsForModesList.get(i_ModeType).remove(i_ModeListener);
    }

    private void removePlugFromAllModeLists(Plug i_Plug)
    {
       //Plug plug = PlugsMediator.getInstance().getPlugAccordingToInternalIndex(i_PlugInternalIndex);
        signedUpPlugsForModesList.forEach(list -> list.remove(i_Plug));
    }

    public void fireEventMode(GenericMode i_EventMode, int i_ModeType) {
        signedUpPlugsForModesList.get(i_ModeType).forEach(genericEvent -> genericEvent.handleMode(i_EventMode));
    }

    public List<IModeListener> getPlugsThatSignedUpForMode(int i_ModeType) {
        return signedUpPlugsForModesList.get(i_ModeType);
    }

    public int GetRandomActivePlugIndex() //returns -1 is not found any
    {
        List<Integer> activePlugsIndexesList = this.plugsList.stream()
                .filter((t) -> t.getOnOffStatus().equals("on"))
                .map(Plug::getInternalPlugIndex).toList();

        int index = !activePlugsIndexesList.isEmpty() ?
                activePlugsIndexesList.get(new Random().nextInt(activePlugsIndexesList.size()))
                : -1;

        if(index != -1){
            for (Plug plug: plugsList) {
                if(plug.getInternalPlugIndex() == index)
                {
                    plug.setInvalidPlugToTrue();
                }
            }
        }

        return index;
    }

    public void RefreshUiIndexes()
    {
        int i = 0;
        for (Plug plug : plugsList) {
            plug.updateUiIndex(i);
            i++;
        }
        UpdateAllPlugsInDB();
    }

    public void RemovePlug(int i_UiIndex, boolean i_WithRefreshUiIndexes) {
        //todo: when we work with the real plug we need to update it accordingly
        Plug plug = GetPlugAccordingToUiIndex(i_UiIndex);
        int internalIndex = plug.getInternalPlugIndex();
        plug.stopTimer();
        plug.KillProcess();
        removePlugFromAllModeLists(plug);
        indexesFreeList.set(internalIndex, true);

        if(i_WithRefreshUiIndexes){
            plugsList.remove(plug);
            RemovePlugFromDB(plug);
            RefreshUiIndexes();
        }
    }

    public boolean CheckIfPlugTitleAlreadyExist(String i_PlugTitle){
        boolean res = false;
        for (Plug plug : plugsList) {
            if (plug.getPlugTitle().equals(i_PlugTitle)) {
                res = true;
                break;
            }
        }

        return res;
    }

    public boolean CheckIfPlugUiIndexAlreadyExist(int i_PlugUiIndex){
        boolean res = false;
        for (Plug plug : plugsList) {
            if (plug.getUiIndex() == i_PlugUiIndex) {
                res = true;
                break;
            }
        }

        return res;
    }

    //************************* Data Base *************************/

    public void SavePlugToDB(Plug plug){
        PlugSave plugSave = new PlugSave(plug);
        plugRepoController.SavePlugToDB(plugSave);
    }

    public void RemovePlugFromDB(Plug plug){
        PlugSave plugSave = new PlugSave(plug);
        plugRepoController.RemovePlugFromDB(plugSave);
    }

    public void UpdateAllPlugsInDB(){
        plugsList.forEach(this::SavePlugToDB);
    }

    public void RemoveAllPlugsInDB(){
        plugsList.forEach(this::RemovePlugFromDB);
    }

    public List<PlugSave> GetPlugsFromDB(){
        return plugRepoController.GetAllPlugsFromDB();
    }

    private boolean checkIfPlugIsInDB(Plug plug) {
        List<PlugSave> plugSaveList = GetPlugsFromDB();
        return plugSaveList.stream().anyMatch(plugSave -> plugSave.getPlugTitle().equals(plug.getPlugTitle()));
    }

    private List<Plug> checkIfPlugIsInDBAndNotOnList() {
        List<PlugSave> plugSaveList = GetPlugsFromDB();
        List<PlugSave> plugSavesOnlyInDB = new ArrayList<>();
        for (PlugSave plugSave : plugSaveList) {
            if (!plugsList.stream().anyMatch(plug -> plug.getPlugTitle().equals(plugSave.getPlugTitle()))) {
                plugSavesOnlyInDB.add(plugSave);
            }
        }
        // convert List<PlugSave> to List<Plug> and return it
        return plugSavesOnlyInDB.stream().map(PlugSave::toPlug).collect(Collectors.toList());
    }

//    private List<Plug> convertPlugSaveListToPlugList(PlugSave PlugSave){
//
//    }


    //************************* Requests to the plug *************************/
    public String sendTurnOnOrOffRequestToPlug(int i_Port, boolean i_TurnOn) {
        String getResponse;
        //String endPoint = "http://172.31.44.173:" + i_Port + "/workshop/plug/turnOnOrOff";
        String endPoint = "http://localhost:" + i_Port + "/workshop/plug/turnOnOrOff";
        HttpUrl.Builder urlBuilder = HttpUrl.parse(endPoint).newBuilder();
        urlBuilder.addQueryParameter("TrueOrFalse", String.valueOf(i_TurnOn));
        Request request = new Request.Builder()
                .url(urlBuilder.build().toString())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            getResponse = response.body().string();

        } catch (IOException e) {
            System.out.println(e.getMessage());
            throw new RuntimeException(e);
        }

        return getResponse;
    }

}
